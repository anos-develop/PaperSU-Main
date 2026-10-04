#![allow(clippy::unreadable_literal)]
use anyhow::{Result, bail};

use crate::ksu_uapi;
use std::cell::Cell;
use std::fs;
use std::io;
use std::os::fd::RawFd;
use std::sync::OnceLock;

// sigsys handler
std::thread_local! {
    #[allow(clippy::missing_const_for_thread_local)]
    static SVC_IN_FLIGHT: Cell<bool> = const { Cell::new(false) };
    #[allow(clippy::missing_const_for_thread_local)]
    static SIGSYS_OCCURRED: Cell<bool> = const { Cell::new(false) };
}

const SYS_SECCOMP: libc::c_int = 1;

fn with_svc_call<F, R>(call: F) -> R
where
    F: FnOnce() -> R,
{
    SVC_IN_FLIGHT.with(|in_flight| in_flight.set(true));
    let result = call();
    SVC_IN_FLIGHT.with(|in_flight| in_flight.set(false));
    result
}

fn take_sigsys_occurred() -> bool {
    SIGSYS_OCCURRED.with(|occurred| occurred.replace(false))
}

extern "C" fn sigsys_handler(
    _sig: libc::c_int,
    info: *mut libc::siginfo_t,
    ctx: *mut libc::c_void,
) {
    unsafe {
        if info.is_null() || ctx.is_null() || (*info).si_code != SYS_SECCOMP {
            return;
        }
        if SVC_IN_FLIGHT.with(Cell::get) {
            SIGSYS_OCCURRED.with(|occurred| occurred.set(true));
        }

        let ucontext = ctx.cast::<libc::ucontext_t>();
        #[cfg(target_arch = "aarch64")]
        {
            (*ucontext).uc_mcontext.regs[0] = (-libc::EPERM) as u64;
        }
        #[cfg(target_arch = "x86_64")]
        {
            let rax = libc::REG_RAX as usize;
            (*ucontext).uc_mcontext.gregs[rax] = i64::from(-libc::EPERM);
        }
    }
}

pub fn setup_sigsys_handler() {
    unsafe {
        let mut sa: libc::sigaction = std::mem::zeroed();
        sa.sa_flags = libc::SA_SIGINFO;
        sa.sa_sigaction = sigsys_handler as *const () as usize;
        libc::sigemptyset(std::ptr::addr_of_mut!(sa.sa_mask));
        if libc::sigaction(libc::SIGSYS, std::ptr::addr_of!(sa), std::ptr::null_mut()) != 0 {
            let error = std::io::Error::last_os_error();
            log::warn!("Failed to set SIGSYS handler: {error}");
        }
    }
}

const DRIVER_FD_NAME: &str = "anon_inode:[ksu_driver]";
const SU_DRIVER_FD_NAME: &str = "anon_inode:[ksu_driver_su]";

// Global driver fd cache
static DRIVER_FD: OnceLock<RawFd> = OnceLock::new();
static INFO_CACHE: OnceLock<ksu_uapi::ksu_get_info_cmd> = OnceLock::new();

fn scan_driver_fd() -> io::Result<Option<RawFd>> {
    let fd_dir = fs::read_dir("/proc/self/fd")?;
    let mut driver_fd = None;

    for entry in fd_dir.flatten() {
        if let Ok(fd_num) = entry.file_name().to_string_lossy().parse::<i32>() {
            let link_path = format!("/proc/self/fd/{fd_num}");
            if let Ok(target) = fs::read_link(&link_path) {
                let target_str = target.to_string_lossy();
                if target_str == SU_DRIVER_FD_NAME {
                    return Ok(Some(fd_num));
                }
                if target_str == DRIVER_FD_NAME {
                    driver_fd = Some(fd_num);
                }
            }
        }
    }

    Ok(driver_fd)
}

pub fn claim_inherited_driver_fd() -> io::Result<()> {
    if DRIVER_FD.get().is_none()
        && let Some(fd) = scan_driver_fd()?
    {
        let _ = DRIVER_FD.set(fd);
    }
    Ok(())
}

// Get cached driver fd
fn init_driver_fd() -> Option<RawFd> {
    let fd = scan_driver_fd().ok().flatten();
    if fd.is_none() {
        let mut fd = -1;
        with_svc_call(|| unsafe {
            libc::syscall(
                libc::SYS_reboot,
                ksu_uapi::KSU_INSTALL_MAGIC1,
                ksu_uapi::KSU_INSTALL_MAGIC2,
                0,
                &mut fd,
            )
        });
        if take_sigsys_occurred() {
            eprintln!("KernelSU driver install syscall was blocked by seccomp");
            log::error!("KernelSU driver install syscall was blocked by seccomp");
        }
        if fd >= 0 { Some(fd) } else { None }
    } else {
        fd
    }
}

// ioctl wrapper using libc
pub fn ksuctl<T>(request: u32, arg: *mut T) -> Result<i32> {
    use std::io;

    let fd = *DRIVER_FD.get_or_init(|| init_driver_fd().unwrap_or(-1));
    if fd < 0 {
        bail!("could not retrieve kernelsu driver fd")
    }
    unsafe {
        let ret = libc::ioctl(fd as libc::c_int, request as i32, arg);
        if ret < 0 {
            bail!("ksuctl failed: {}", io::Error::last_os_error())
        }
        Ok(ret)
    }
}

// API implementations
pub fn get_info() -> ksu_uapi::ksu_get_info_cmd {
    *INFO_CACHE.get_or_init(|| {
        let mut cmd = ksu_uapi::ksu_get_info_cmd {
            version: 0,
            flags: 0,
            features: 0,
            uapi_version: 0,
        };
        if ksuctl(ksu_uapi::KSU_IOCTL_GET_INFO, &raw mut cmd).is_err() {
            let _ = ksuctl(ksu_uapi::KSU_IOCTL_GET_INFO_LEGACY, &raw mut cmd);
        }
        cmd
    })
}

pub fn get_version() -> i32 {
    get_info().version as i32
}

pub fn is_late_load() -> bool {
    get_info().flags & ksu_uapi::KSU_GET_INFO_FLAG_LATE_LOAD != 0
}

pub fn is_lkm() -> bool {
    get_info().flags & ksu_uapi::KSU_GET_INFO_FLAG_LKM != 0
}

pub const fn uapi_version() -> u32 {
    ksu_uapi::KERNEL_SU_UAPI_VERSION
}

pub fn runtime_mode() -> &'static str {
    if is_late_load() {
        "late-load"
    } else if is_lkm() {
        "lkm"
    } else {
        "built-in"
    }
}

pub fn ensure_uapi_version_matched() -> anyhow::Result<()> {
    let kernel_uapi = get_info().uapi_version;
    let userspace_uapi = uapi_version();
    if kernel_uapi != userspace_uapi {
        bail!(
            "UAPI version mismatch: kernel={kernel_uapi}, ksud={userspace_uapi}. Please update KernelSU!"
        );
    }
    Ok(())
}

pub fn grant_root() -> Result<()> {
    ksuctl(ksu_uapi::KSU_IOCTL_GRANT_ROOT, std::ptr::null_mut::<u8>())?;
    Ok(())
}

fn report_event(event: u32) {
    let mut cmd = ksu_uapi::ksu_report_event_cmd { event };
    let _ = ksuctl(ksu_uapi::KSU_IOCTL_REPORT_EVENT, &raw mut cmd);
}

pub fn report_post_fs_data() {
    report_event(ksu_uapi::EVENT_POST_FS_DATA);
}

pub fn report_boot_complete() {
    report_event(ksu_uapi::EVENT_BOOT_COMPLETED);
}

pub fn report_module_mounted() {
    report_event(ksu_uapi::EVENT_MODULE_MOUNTED);
}

pub fn check_kernel_safemode() -> bool {
    let mut cmd = ksu_uapi::ksu_check_safemode_cmd { in_safe_mode: 0 };
    let _ = ksuctl(ksu_uapi::KSU_IOCTL_CHECK_SAFEMODE, &raw mut cmd);
    cmd.in_safe_mode != 0
}

pub fn set_sepolicy(payload: *const u8, payload_len: u64) -> Result<i32> {
    let mut ioctl_cmd = crate::ksu_uapi::ksu_set_sepolicy_cmd {
        data_len: payload_len,
        data: payload as u64,
    };

    ksuctl(ksu_uapi::KSU_IOCTL_SET_SEPOLICY, &raw mut ioctl_cmd)
}

/// Get feature value and support status from kernel
/// Returns (value, supported)
pub fn get_feature(feature_id: u32) -> Result<(u64, bool)> {
    let mut cmd = ksu_uapi::ksu_get_feature_cmd {
        feature_id,
        value: 0,
        supported: 0,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_GET_FEATURE, &raw mut cmd)?;
    Ok((cmd.value, cmd.supported != 0))
}

/// Set feature value in kernel
pub fn set_feature(feature_id: u32, value: u64) -> Result<()> {
    let mut cmd = ksu_uapi::ksu_set_feature_cmd { feature_id, value };
    ksuctl(ksu_uapi::KSU_IOCTL_SET_FEATURE, &raw mut cmd)?;
    Ok(())
}

pub fn get_wrapped_fd(fd: RawFd) -> Result<RawFd> {
    let mut cmd = ksu_uapi::ksu_get_wrapper_fd_cmd {
        fd: fd as u32,
        flags: 0,
    };
    let result = ksuctl(ksu_uapi::KSU_IOCTL_GET_WRAPPER_FD, &raw mut cmd)?;
    Ok(result)
}

pub fn get_sulog_fd() -> Result<RawFd> {
    let mut cmd = ksu_uapi::ksu_get_sulog_fd_cmd { flags: 0 };
    let result = ksuctl(ksu_uapi::KSU_IOCTL_GET_SULOG_FD, &raw mut cmd)?;
    Ok(result)
}

/// Get mark status for a process (pid=0 returns total marked count)
pub fn mark_get(pid: i32) -> Result<u32> {
    let mut cmd = ksu_uapi::ksu_manage_mark_cmd {
        operation: ksu_uapi::KSU_MARK_GET,
        pid,
        result: 0,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_MANAGE_MARK, &raw mut cmd)?;
    Ok(cmd.result)
}

/// Mark a process (pid=0 marks all processes)
pub fn mark_set(pid: i32) -> Result<()> {
    let mut cmd = ksu_uapi::ksu_manage_mark_cmd {
        operation: ksu_uapi::KSU_MARK_MARK,
        pid,
        result: 0,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_MANAGE_MARK, &raw mut cmd)?;
    Ok(())
}

/// Unmark a process (pid=0 unmarks all processes)
pub fn mark_unset(pid: i32) -> Result<()> {
    let mut cmd = ksu_uapi::ksu_manage_mark_cmd {
        operation: ksu_uapi::KSU_MARK_UNMARK,
        pid,
        result: 0,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_MANAGE_MARK, &raw mut cmd)?;
    Ok(())
}

/// Refresh mark for all running processes
pub fn mark_refresh() -> Result<()> {
    let mut cmd = ksu_uapi::ksu_manage_mark_cmd {
        operation: ksu_uapi::KSU_MARK_REFRESH,
        pid: 0,
        result: 0,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_MANAGE_MARK, &raw mut cmd)?;
    Ok(())
}

pub fn nuke_ext4_sysfs(mnt: &str) -> anyhow::Result<()> {
    let c_mnt = std::ffi::CString::new(mnt)?;
    let mut ioctl_cmd = ksu_uapi::ksu_nuke_ext4_sysfs_cmd {
        arg: c_mnt.as_ptr() as u64,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_NUKE_EXT4_SYSFS, &raw mut ioctl_cmd)?;
    Ok(())
}

/// Wipe all entries from umount list
pub fn umount_list_wipe() -> Result<()> {
    let mut cmd = ksu_uapi::ksu_add_try_umount_cmd {
        arg: 0,
        flags: 0,
        mode: ksu_uapi::KSU_UMOUNT_WIPE,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_ADD_TRY_UMOUNT, &raw mut cmd)?;
    Ok(())
}

/// Add mount point to umount list
pub fn umount_list_add(path: &str, flags: u32) -> anyhow::Result<()> {
    let c_path = std::ffi::CString::new(path)?;
    let mut cmd = ksu_uapi::ksu_add_try_umount_cmd {
        arg: c_path.as_ptr() as u64,
        flags,
        mode: ksu_uapi::KSU_UMOUNT_ADD,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_ADD_TRY_UMOUNT, &raw mut cmd)?;
    Ok(())
}

/// Delete mount point from umount list
pub fn umount_list_del(path: &str) -> anyhow::Result<()> {
    let c_path = std::ffi::CString::new(path)?;
    let mut cmd = ksu_uapi::ksu_add_try_umount_cmd {
        arg: c_path.as_ptr() as u64,
        flags: 0,
        mode: ksu_uapi::KSU_UMOUNT_DEL,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_ADD_TRY_UMOUNT, &raw mut cmd)?;
    Ok(())
}

/// Set current process's process group to init_group (pgid = 0)
pub fn set_init_pgrp() -> Result<()> {
    ksuctl(
        ksu_uapi::KSU_IOCTL_SET_INIT_PGRP,
        std::ptr::null_mut::<u8>(),
    )?;
    Ok(())
}

pub fn set_ksu_no_new_privs() -> anyhow::Result<()> {
    let result = ksuctl(
        ksu_uapi::KSU_IOCTL_DISABLE_ESCAPE_TO_ROOT,
        std::ptr::null_mut::<u8>(),
    )?;
    if result != 0 {
        bail!("unexpected result: {result}");
    }
    Ok(())
}

/// List all mount points in umount list
pub fn umount_list_list() -> anyhow::Result<String> {
    const BUF_SIZE: usize = 4096;
    let mut buffer = vec![0u8; BUF_SIZE];
    let mut cmd = ksu_uapi::ksu_list_try_umount_cmd {
        arg: buffer.as_mut_ptr() as u64,
        buf_size: BUF_SIZE as u32,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_LIST_TRY_UMOUNT, &raw mut cmd)?;

    // Find null terminator or end of buffer
    let len = buffer.iter().position(|&b| b == 0).unwrap_or(BUF_SIZE);
    let result = String::from_utf8_lossy(&buffer[..len]).to_string();
    Ok(result)
}

pub fn set_spoof_version(release: &str, version: &str) -> anyhow::Result<()> {
    let mut cmd = ksu_uapi::ksu_set_spoof_version_cmd {
        release: [0; 65],
        version: [0; 65],
    };

    let r_bytes = release.as_bytes();
    let r_len = std::cmp::min(r_bytes.len(), 64);
    cmd.release[..r_len].copy_from_slice(&r_bytes[..r_len]);

    let v_bytes = version.as_bytes();
    let v_len = std::cmp::min(v_bytes.len(), 64);
    cmd.version[..v_len].copy_from_slice(&v_bytes[..v_len]);

    ksuctl(ksu_uapi::KSU_IOCTL_SET_SPOOF_VERSION, &raw mut cmd)?;
    Ok(())
}

pub fn set_spoof_cpu(
    cpu_index: u32,
    midr: u32,
    bogomips: u32,
    hwcap: u64,
    hwcap2: u64,
) -> anyhow::Result<()> {
    let mut cmd = ksu_uapi::ksu_set_spoof_cpu_cmd {
        cpu_index,
        midr,
        bogomips,
        hwcap,
        hwcap2,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_SET_SPOOF_CPU, &raw mut cmd)?;
    Ok(())
}

// ══════════════════════════════════════════════════════════════════════════════
// paperSU: webadmin support, ported from 7kimisu (GPL-3.0-or-later).
//
// The local web admin page (`webadmin.rs` / `webadmin_ksud.rs`) needs three things ksud
// did not have: the allow/deny uid lists, the manager appid, and AppProfile read/write.
// `KSU_IOCTL_{GET,SET}_APP_PROFILE` are `only_manager` in the kernel
// (`kernel/supercall/dispatch.c`), so a plain root call gets EPERM - hence the
// fork + setresuid(manager uid) + rescan-the-driver-fd dance below. That part is a
// faithful copy of 7kimisu's implementation, exit-code contract included.
// ══════════════════════════════════════════════════════════════════════════════

/// Uids with `allow_su == true`, i.e. apps that were granted root.
///
/// The kernel's list ioctl already excludes the manager itself, so this really is
/// "apps holding root".
pub fn allow_list_uids() -> Vec<u32> {
    list_uids(true)
}

/// Uids that only carry a **non-root** profile (`allow_su == false`).
///
/// The default non-root template (key `"$"`, uid 9999) also shows up here; callers filter
/// it out by package name.
pub fn deny_list_uids() -> Vec<u32> {
    list_uids(false)
}

fn list_uids(allow: bool) -> Vec<u32> {
    use ksu_uapi::{
        KSU_IOCTL_NEW_GET_ALLOW_LIST, KSU_IOCTL_NEW_GET_DENY_LIST, ksu_new_get_allow_list_cmd,
    };
    let req = if allow {
        KSU_IOCTL_NEW_GET_ALLOW_LIST
    } else {
        KSU_IOCTL_NEW_GET_DENY_LIST
    };
    // Ask for the count first.
    let mut probe: ksu_new_get_allow_list_cmd = unsafe { std::mem::zeroed() };
    probe.count = 0;
    if ksuctl(req, &raw mut probe).is_err() {
        return Vec::new();
    }
    let total = probe.total_count as usize;
    if total == 0 {
        return Vec::new();
    }
    // The struct ends in a flexible array (`uids[0]`), so allocate the extra bytes by hand.
    // ⚠️ Allocate as u32: a Vec<u8> only guarantees 1-byte alignment, and writing struct
    //    fields through such a pointer is a misaligned write (what clippy's
    //    cast_ptr_alignment warns about - a real hazard, not a style nit).
    let base = std::mem::size_of::<ksu_new_get_allow_list_cmd>();
    let total = total.min(4096);
    let need = base + total * std::mem::size_of::<u32>();
    let mut buf: Vec<u32> = vec![0; need.div_ceil(std::mem::size_of::<u32>())];
    let cmd = buf.as_mut_ptr().cast::<ksu_new_get_allow_list_cmd>();
    unsafe {
        (*cmd).count = total as u16;
        (*cmd).total_count = 0;
    }
    if ksuctl(req, cmd).is_err() {
        return Vec::new();
    }
    let n = unsafe { (*cmd).count as usize }.min(total);
    let ptr = unsafe { (*cmd).uids.as_ptr() };
    unsafe { std::slice::from_raw_parts(ptr, n) }.to_vec()
}

/// Read the manager app's appid (the kernel lets root ask for this one directly).
pub fn manager_appid() -> anyhow::Result<u32> {
    let mut cmd: ksu_uapi::ksu_get_manager_appid_cmd = unsafe { std::mem::zeroed() };
    ksuctl(ksu_uapi::KSU_IOCTL_GET_MANAGER_APPID, &raw mut cmd)?;
    Ok(cmd.appid)
}

/// Copy a Rust string into a fixed `char[N]` (truncate + NUL-terminate).
pub fn write_cstr(buf: &mut [libc::c_char], s: &str) {
    let n = s.len().min(buf.len().saturating_sub(1));
    for (i, b) in s.as_bytes()[..n].iter().enumerate() {
        buf[i] = *b as libc::c_char;
    }
    if let Some(last) = buf.get_mut(n) {
        *last = 0;
    }
}

/// Read a Rust string back out of a fixed `char[N]`.
#[allow(clippy::unnecessary_cast)] // x86_64 has c_char = i8, aarch64 has u8: the cast is load-bearing
pub fn read_cstr(buf: &[libc::c_char]) -> String {
    let bytes: Vec<u8> = buf
        .iter()
        .take_while(|c| **c != 0)
        .map(|c| *c as u8)
        .collect();
    String::from_utf8_lossy(&bytes).to_string()
}

/// Upper bound for the request struct handed to the manager-identity ioctl.
const MANAGER_IOCTL_MAX: usize = 4096;

/// Run one ioctl with manager identity.
///
/// `Ok(Some(bytes))` = success (the struct the kernel wrote back); `Ok(None)` = the kernel
/// answered `-ENOENT`, which for a GET means "this uid has no profile" - a normal state,
/// not an error.
///
/// The child **allocates nothing** (the safe thing to do after fork in a multithreaded
/// process): the request sits in a fixed stack array and the reply travels back over a pipe.
/// Exit codes: 10=setuid failed 11=no manager fd 12=kernel refused 13=write-back failed
/// 20=-ENOENT.
fn ioctl_as_manager(req: u32, input: &[u8]) -> anyhow::Result<Option<Vec<u8>>> {
    anyhow::ensure!(input.len() <= MANAGER_IOCTL_MAX, "request struct too large");
    let appid = manager_appid()?;
    if appid == u32::MAX || appid == 0 {
        anyhow::bail!("cannot obtain the manager appid");
    }
    let inherited = DRIVER_FD.get().copied().unwrap_or(-1);
    let len = input.len();

    let mut fds = [0i32; 2];
    unsafe {
        if libc::pipe(fds.as_mut_ptr()) != 0 {
            anyhow::bail!("pipe failed: {}", io::Error::last_os_error());
        }
    }
    let [rd, wr] = fds;
    let mut out = vec![0u8; len];

    unsafe {
        let pid = libc::fork();
        if pid == 0 {
            // ---- child ----
            libc::close(rd);
            let mut buf = [0u8; MANAGER_IOCTL_MAX];
            std::ptr::copy_nonoverlapping(input.as_ptr(), buf.as_mut_ptr(), len);
            if libc::setresuid(appid, appid, appid) != 0 {
                libc::_exit(10);
            }
            // Drop the inherited handle so the rescan cannot pick it up again: it carries
            // no manager permission bit.
            if inherited >= 0 {
                libc::close(inherited);
            }
            // The handle the kernel installed when we called setresuid.
            let Ok(Some(fd)) = scan_driver_fd() else {
                libc::_exit(11);
            };
            let ret = libc::ioctl(fd, req as libc::c_int, buf.as_mut_ptr());
            if ret != 0 {
                let errno = std::io::Error::last_os_error().raw_os_error().unwrap_or(0);
                libc::_exit(if errno == libc::ENOENT { 20 } else { 12 });
            }
            let mut off = 0usize;
            while off < len {
                let n = libc::write(wr, buf.as_ptr().add(off).cast::<libc::c_void>(), len - off);
                if n <= 0 {
                    libc::_exit(13);
                }
                off += n as usize;
            }
            libc::close(wr);
            libc::_exit(0);
        } else if pid < 0 {
            libc::close(rd);
            libc::close(wr);
            anyhow::bail!("fork failed: {}", io::Error::last_os_error());
        }

        libc::close(wr);
        // The request is far smaller than the 64 KiB pipe buffer, so this read cannot
        // deadlock against the child's write.
        let mut off = 0usize;
        while off < len {
            let n = libc::read(rd, out.as_mut_ptr().add(off).cast::<libc::c_void>(), len - off);
            if n <= 0 {
                break;
            }
            off += n as usize;
        }
        libc::close(rd);
        let mut status: libc::c_int = 0;
        libc::waitpid(pid, &raw mut status, 0);
        if !libc::WIFEXITED(status) {
            anyhow::bail!("manager-identity child died abnormally");
        }
        match libc::WEXITSTATUS(status) {
            0 => {}
            20 => return Ok(None),
            c => anyhow::bail!(
                "ioctl failed (child exit {c}: 10=setuid 11=no manager fd 12=kernel refused 13=write-back)"
            ),
        }
        anyhow::ensure!(off == len, "ioctl reply truncated ({off}/{len})");
    }
    Ok(Some(out))
}

const fn struct_bytes<T>(v: &T) -> &[u8] {
    let p = std::ptr::from_ref(v).cast::<u8>();
    unsafe { std::slice::from_raw_parts(p, std::mem::size_of::<T>()) }
}

fn struct_from_bytes<T: Copy>(b: &[u8]) -> anyhow::Result<T> {
    anyhow::ensure!(
        b.len() >= std::mem::size_of::<T>(),
        "not enough bytes for the struct"
    );
    // These are all C PODs (integers, fixed char arrays, bools), so all-zero is valid.
    let mut v: T = unsafe { std::mem::zeroed() };
    unsafe {
        std::ptr::copy_nonoverlapping(
            b.as_ptr(),
            std::ptr::addr_of_mut!(v).cast::<u8>(),
            std::mem::size_of::<T>(),
        );
    }
    Ok(v)
}

/// Read one app's AppProfile.
///
/// `Ok(None)` = the kernel has no entry for this uid (never granted, never configured);
/// the UI then shows defaults.
pub fn get_app_profile(uid: u32, key: &str) -> anyhow::Result<Option<ksu_uapi::app_profile>> {
    let mut cmd: ksu_uapi::ksu_get_app_profile_cmd = unsafe { std::mem::zeroed() };
    cmd.profile.version = ksu_uapi::KSU_APP_PROFILE_VER;
    cmd.profile.curr_uid = uid as i32;
    write_cstr(&mut cmd.profile.key[..], key);
    match ioctl_as_manager(ksu_uapi::KSU_IOCTL_GET_APP_PROFILE, struct_bytes(&cmd))? {
        None => Ok(None),
        Some(out) => {
            let got: ksu_uapi::ksu_get_app_profile_cmd = struct_from_bytes(&out)?;
            Ok(Some(got.profile))
        }
    }
}

/// Write an AppProfile (granting/revoking root and editing root config all go through here).
pub fn set_app_profile(profile: &ksu_uapi::app_profile) -> anyhow::Result<()> {
    let mut cmd: ksu_uapi::ksu_set_app_profile_cmd = unsafe { std::mem::zeroed() };
    cmd.profile = *profile;
    match ioctl_as_manager(ksu_uapi::KSU_IOCTL_SET_APP_PROFILE, struct_bytes(&cmd))? {
        Some(_) => Ok(()),
        None => anyhow::bail!("the kernel reports no such profile"),
    }
}

// ---- Hidden mode: deliberately not wired to the kernel --------------------------------
//
// 7kimisu implements hidden mode as a *kernel* flag (`KSU_IOCTL_STEALTH_{GET,SET}`, its
// UAPI 5) and drives it with the same manager-identity dance. paperSU's hidden mode lives
// in the manager instead: the app flips a mask inside the loaded native library so that
// `Natives.isManager` reports false (see `ui/security/Stealth.kt`). That needs no kernel
// support, which is exactly why it behaves identically for boot / init_boot / LKM /
// built-in / GKI.
//
// The web admin page still asks about it, so answer honestly rather than pretend: reads
// report "off", and writes return an explanation that the page shows to the user.

/// paperSU keeps hidden mode in the manager, so ksud cannot observe it: report "off".
pub fn stealth_get_authed() -> anyhow::Result<bool> {
    Ok(false)
}

/// Not available through the web page - see the note above.
pub fn stealth_set_authed(_enabled: bool) -> anyhow::Result<()> {
    anyhow::bail!(
        "this build keeps hidden mode in the manager app; toggle it from the app settings"
    )
}
