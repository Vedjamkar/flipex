//! FlipEx for Mac and Windows: finds the phone (USB or Wi-Fi), pairs it, and opens Samsung DeX
//! in a window through scrcpy, the same way scripts/dex-on-mac.sh does.
//!
//! scrcpy and adb ship inside the app (resources/scrcpy). The bundled adb runs its own server on
//! port 5038 so it never fights an Android Studio / SDK adb on the default port 5037.

use serde::Serialize;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::{AppHandle, Emitter, Manager, State};

const BUNDLED_ADB_PORT: &str = "5038";
const FLIPEX_PACKAGE: &str = "dev.ved.flipdex";
const SHIZUKU_PACKAGE: &str = "moe.shizuku.privileged.api";

/// adb and scrcpy this app uses, and whether they're the bundled copies.
#[derive(Clone, Serialize)]
struct Tools {
    adb: Option<String>,
    scrcpy: Option<String>,
    server: Option<String>,
    bundled: bool,
}

#[derive(Default)]
struct AppState {
    tools: Mutex<Option<Tools>>,
    dex: Mutex<Option<Child>>,
    qr: Mutex<Option<(String, String)>>, // (service name, password) of a QR pairing in progress
    /// Since when no phone has been connected or seen nearby, and when the bundled adb server was
    /// last restarted: adb's Wi-Fi discovery can go quiet (for example when it starts while the
    /// phone is switching wireless debugging on), and only a server restart brings it back.
    quiet_since: Mutex<Option<Instant>>,
    last_restart: Mutex<Option<Instant>>,
}

/// How long nothing is found before the bundled adb server is restarted, and how often at most.
const QUIET_RESTART_AFTER: Duration = Duration::from_secs(8);
const RESTART_EVERY: Duration = Duration::from_secs(30);

#[derive(Serialize)]
struct Device {
    serial: String,
    model: String,
    wifi: bool,
    state: String,
}

#[derive(Serialize)]
struct Status {
    tools: Tools,
    devices: Vec<Device>,
    /// Phones advertising wireless debugging that adb isn't connected to yet ("name address").
    nearby: Vec<String>,
    dex_running: bool,
}

// ---------------------------------------------------------------- tools and processes

fn exe(name: &str) -> String {
    if cfg!(windows) {
        format!("{name}.exe")
    } else {
        name.to_string()
    }
}

fn find_on_path(name: &str) -> Option<PathBuf> {
    let mut dirs: Vec<PathBuf> = std::env::var_os("PATH")
        .map(|p| std::env::split_paths(&p).collect())
        .unwrap_or_default();
    // GUI apps on macOS don't get the shell's PATH.
    dirs.extend(["/opt/homebrew/bin", "/usr/local/bin"].iter().map(PathBuf::from));
    if let Some(home) = std::env::var_os("HOME") {
        dirs.push(Path::new(&home).join("Library/Android/sdk/platform-tools"));
    }
    if let Some(sdk) = std::env::var_os("ANDROID_HOME") {
        dirs.push(Path::new(&sdk).join("platform-tools"));
    }
    dirs.into_iter().map(|d| d.join(exe(name))).find(|p| p.is_file())
}

fn resolve_tools(app: &AppHandle) -> Tools {
    if let Ok(res) = app.path().resource_dir() {
        let dir = res.join("scrcpy");
        let (adb, scrcpy, server) = (dir.join(exe("adb")), dir.join(exe("scrcpy")), dir.join("scrcpy-server"));
        if adb.is_file() && scrcpy.is_file() && server.is_file() {
            return Tools {
                adb: Some(adb.display().to_string()),
                scrcpy: Some(scrcpy.display().to_string()),
                server: Some(server.display().to_string()),
                bundled: true,
            };
        }
    }
    // Development builds without the bundle: whatever is installed.
    Tools {
        adb: find_on_path("adb").map(|p| p.display().to_string()),
        scrcpy: find_on_path("scrcpy").map(|p| p.display().to_string()),
        server: None,
        bundled: false,
    }
}

fn tools(app: &AppHandle, state: &AppState) -> Tools {
    let mut t = state.tools.lock().unwrap();
    if t.is_none() {
        *t = Some(resolve_tools(app));
    }
    t.clone().unwrap()
}

/// A command with FlipEx's environment: the bundled adb's own server port, scrcpy pointed at the
/// same adb and server file, and no console window flashing up on Windows.
fn command(t: &Tools, program: &str) -> Command {
    let mut c = Command::new(program);
    if t.bundled {
        c.env("ANDROID_ADB_SERVER_PORT", BUNDLED_ADB_PORT);
    }
    if let Some(adb) = &t.adb {
        c.env("ADB", adb);
    }
    if let Some(server) = &t.server {
        c.env("SCRCPY_SERVER_PATH", server);
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        c.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    c
}

/// Runs adb and returns stdout+stderr. Err if adb is missing or couldn't start.
fn adb(t: &Tools, args: &[&str]) -> Result<String, String> {
    let path = t.adb.as_deref().ok_or("adb not found")?;
    let out = command(t, path)
        .args(args)
        .stdin(Stdio::null())
        .output()
        .map_err(|e| format!("couldn't run adb: {e}"))?;
    let mut s = String::from_utf8_lossy(&out.stdout).to_string();
    s.push_str(&String::from_utf8_lossy(&out.stderr));
    Ok(s)
}

fn adb_on(t: &Tools, serial: &str, args: &[&str]) -> Result<String, String> {
    let mut all = vec!["-s", serial];
    all.extend_from_slice(args);
    adb(t, &all)
}

// ---------------------------------------------------------------- finding the phone

fn devices(t: &Tools) -> Vec<Device> {
    let out = adb(t, &["devices", "-l"]).unwrap_or_default();
    out.lines()
        .skip(1)
        .filter_map(|l| {
            let mut parts = l.split_whitespace();
            let serial = parts.next()?.to_string();
            let state = parts.next()?.to_string();
            let model = l
                .split_whitespace()
                .find_map(|p| p.strip_prefix("model:"))
                .unwrap_or("")
                .replace('_', " ");
            let wifi = serial.contains(':') || serial.contains("._adb-tls-connect.");
            Some(Device { serial, model, wifi, state })
        })
        .collect()
}

/// (instance name, service type, address) from `adb mdns services`.
fn mdns(t: &Tools) -> Vec<(String, String, String)> {
    let out = adb(t, &["mdns", "services"]).unwrap_or_default();
    out.lines()
        .filter_map(|l| {
            // Tab-separated; instance names can contain spaces ("adb-XYZ (2)").
            let f: Vec<&str> = l.split('\t').map(str::trim).collect();
            if f.len() >= 3 && f[1].starts_with("_adb-tls-") {
                Some((f[0].to_string(), f[1].trim_end_matches('.').to_string(), f[2].to_string()))
            } else {
                None
            }
        })
        .collect()
}

#[tauri::command]
async fn status(app: AppHandle, state: State<'_, AppState>) -> Result<Status, String> {
    let t = tools(&app, &state);
    let running = {
        let mut dex = state.dex.lock().unwrap();
        match dex.as_mut().map(|c| c.try_wait()) {
            Some(Ok(None)) => true,
            _ => {
                *dex = None;
                false
            }
        }
    };
    let t2 = t.clone();
    let (devices, nearby) = tauri::async_runtime::spawn_blocking(move || {
        let devs = devices(&t2);
        let nearby: Vec<String> = mdns(&t2)
            .into_iter()
            .filter(|(name, kind, addr)| {
                kind == "_adb-tls-connect._tcp"
                    && !devs.iter().any(|d| d.serial == *addr || d.serial.starts_with(name.as_str()))
            })
            .map(|(name, _, addr)| format!("{name} {addr}"))
            .collect();
        (devs, nearby)
    })
    .await
    .map_err(|e| e.to_string())?;

    // Nothing connected and nothing nearby for a while: restart our own adb server so its Wi-Fi
    // discovery starts over. Only the bundled server (port 5038), never the user's own adb.
    if t.bundled && !running && devices.is_empty() && nearby.is_empty() {
        let now = Instant::now();
        let quiet = *state.quiet_since.lock().unwrap().get_or_insert(now);
        let mut last = state.last_restart.lock().unwrap();
        if now - quiet >= QUIET_RESTART_AFTER && last.map_or(true, |l| now - l >= RESTART_EVERY) {
            *last = Some(now);
            let t3 = t.clone();
            tauri::async_runtime::spawn_blocking(move || {
                let _ = adb(&t3, &["kill-server"]);
                let _ = adb(&t3, &["start-server"]);
            });
        }
    } else {
        *state.quiet_since.lock().unwrap() = None;
    }
    Ok(Status { tools: t, devices, nearby, dex_running: running })
}

#[tauri::command]
async fn connect(app: AppHandle, state: State<'_, AppState>, address: String) -> Result<String, String> {
    let t = tools(&app, &state);
    tauri::async_runtime::spawn_blocking(move || adb(&t, &["connect", &address]))
        .await
        .map_err(|e| e.to_string())?
}

/// Pairing with the six-digit code from the phone's "Pair device with pairing code".
#[tauri::command]
async fn pair(app: AppHandle, state: State<'_, AppState>, address: String, code: String) -> Result<String, String> {
    let t = tools(&app, &state);
    tauri::async_runtime::spawn_blocking(move || adb(&t, &["pair", address.trim(), code.trim()]))
        .await
        .map_err(|e| e.to_string())?
}

// ---------------------------------------------------------------- QR pairing

/// Starts QR pairing: returns an SVG QR code for the phone's "Pair device with QR code".
/// The phone then advertises a pairing service with this name; `qr_poll` pairs with it.
#[tauri::command]
fn qr_start(state: State<'_, AppState>) -> Result<String, String> {
    use rand::Rng;
    const CHARS: &[u8] = b"abcdefghijkmnpqrstuvwxyz23456789";
    let mut rng = rand::thread_rng();
    let mut pick = |n: usize| -> String {
        (0..n).map(|_| CHARS[rng.gen_range(0..CHARS.len())] as char).collect()
    };
    let name = format!("flipex-{}", pick(8));
    let password = pick(10);
    let payload = format!("WIFI:T:ADB;S:{name};P:{password};;");
    let code = qrcode::QrCode::new(payload.as_bytes()).map_err(|e| e.to_string())?;
    let svg = code
        .render::<qrcode::render::svg::Color>()
        .min_dimensions(220, 220)
        .quiet_zone(true)
        .build();
    *state.qr.lock().unwrap() = Some((name, password));
    Ok(svg)
}

/// Checks once whether the phone has scanned the code; pairs if so. Returns "waiting",
/// "paired" or an error message.
#[tauri::command]
async fn qr_poll(app: AppHandle, state: State<'_, AppState>) -> Result<String, String> {
    let t = tools(&app, &state);
    let Some((name, password)) = state.qr.lock().unwrap().clone() else {
        return Ok("idle".into());
    };
    let result = tauri::async_runtime::spawn_blocking(move || -> Result<String, String> {
        let found = mdns(&t)
            .into_iter()
            .find(|(n, kind, _)| n == &name && kind == "_adb-tls-pairing._tcp");
        let Some((_, _, addr)) = found else { return Ok("waiting".into()) };
        let out = adb(&t, &["pair", &addr, &password])?;
        if out.to_lowercase().contains("successfully paired") {
            Ok("paired".into())
        } else {
            Err(out.trim().to_string())
        }
    })
    .await
    .map_err(|e| e.to_string())??;
    if result != "waiting" {
        *state.qr.lock().unwrap() = None;
    }
    Ok(result)
}

// ---------------------------------------------------------------- DeX

/// Turns on Android's desktop mode, opens FlipEx (its session sets up DeX features, the
/// taskbar desk, keyboard and mouse) and opens scrcpy on a new display, where Android starts
/// Samsung's DeX home. Same flags as scripts/dex-on-mac.sh.
#[tauri::command]
async fn start_dex(
    app: AppHandle,
    state: State<'_, AppState>,
    serial: String,
    size: String,
    dark: bool,
) -> Result<String, String> {
    let t = tools(&app, &state);
    let scrcpy = t.scrcpy.clone().ok_or("scrcpy not found")?;
    if let Some(mut old) = state.dex.lock().unwrap().take() {
        let _ = old.kill();
    }
    let t2 = t.clone();
    let s2 = serial.clone();
    tauri::async_runtime::spawn_blocking(move || -> Result<(), String> {
        for key in ["enable_freeform_support", "force_desktop_mode_on_external_displays", "enable_non_resizable_multi_window"] {
            adb_on(&t2, &s2, &["shell", "settings", "put", "global", key, "1"])?;
        }
        let _ = adb_on(&t2, &s2, &["shell", "am", "start", "-n", &format!("{FLIPEX_PACKAGE}/.MainActivity")]);
        Ok(())
    })
    .await
    .map_err(|e| e.to_string())??;

    let mut args: Vec<String> = vec![
        "-s".into(),
        serial.clone(),
        format!("--new-display={size}"),
        "--no-vd-destroy-content".into(),
        "--window-title=FlipEx DeX".into(),
        "--mouse=sdk".into(),
        "--mouse-bind=++++:bhsn".into(),
        "--keyboard=uhid".into(),
        "--max-fps=30".into(),
        "--video-bit-rate=8M".into(),
        "--stay-awake".into(),
        "--no-audio".into(),
    ];
    if dark {
        args.push("--turn-screen-off".into());
    }
    let mut child = command(&t, &scrcpy)
        .args(&args)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| format!("couldn't start scrcpy: {e}"))?;

    // Tell the window when scrcpy ends, with its last error lines.
    let stderr = child.stderr.take();
    let handle = app.clone();
    std::thread::spawn(move || {
        use std::io::{BufRead, BufReader};
        let mut tail: Vec<String> = Vec::new();
        if let Some(err) = stderr {
            for line in BufReader::new(err).lines().map_while(Result::ok) {
                tail.push(line);
                if tail.len() > 6 {
                    tail.remove(0);
                }
            }
        }
        let _ = handle.emit("dex-ended", tail.join("\n"));
    });
    *state.dex.lock().unwrap() = Some(child);
    Ok("DeX window opening…".into())
}

#[tauri::command]
fn stop_dex(state: State<'_, AppState>) -> Result<(), String> {
    if let Some(mut c) = state.dex.lock().unwrap().take() {
        let _ = c.kill();
    }
    Ok(())
}

/// Starts Shizuku with its own starter (needed after every phone reboot).
#[tauri::command]
async fn start_shizuku(app: AppHandle, state: State<'_, AppState>, serial: String) -> Result<String, String> {
    let t = tools(&app, &state);
    tauri::async_runtime::spawn_blocking(move || {
        let path = adb_on(&t, &serial, &["shell", "pm", "path", SHIZUKU_PACKAGE])?;
        let apk = path.lines().find_map(|l| l.trim().strip_prefix("package:")).ok_or("Shizuku isn't installed on the phone")?;
        let dir = apk.trim_end_matches("/base.apk");
        let out = adb_on(&t, &serial, &["shell", &format!("{dir}/lib/arm64/libshizuku.so")])?;
        if out.contains("exit with 0") || out.contains("shizuku_server pid") {
            Ok("Shizuku is running.".to_string())
        } else {
            Err(out.trim().to_string())
        }
    })
    .await
    .map_err(|e| e.to_string())?
}

/// Puts Android's desktop settings back to stock (FlipEx's own Restore also resets its extras).
#[tauri::command]
async fn restore(app: AppHandle, state: State<'_, AppState>, serial: String) -> Result<String, String> {
    let t = tools(&app, &state);
    tauri::async_runtime::spawn_blocking(move || -> Result<String, String> {
        for key in ["enable_freeform_support", "force_desktop_mode_on_external_displays", "enable_non_resizable_multi_window"] {
            adb_on(&t, &serial, &["shell", "settings", "put", "global", key, "0"])?;
        }
        adb_on(&t, &serial, &["shell", "settings", "delete", "global", "overlay_display_devices"])?;
        Ok("Desktop settings restored to stock.".into())
    })
    .await
    .map_err(|e| e.to_string())?
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .manage(AppState::default())
        .invoke_handler(tauri::generate_handler![
            status, connect, pair, qr_start, qr_poll, start_dex, stop_dex, start_shizuku, restore
        ])
        .build(tauri::generate_context!())
        .expect("couldn't start FlipEx")
        .run(|app, event| {
            // Closing FlipEx closes the DeX window too (apps move back to the phone).
            if let tauri::RunEvent::Exit = event {
                if let Some(mut c) = app.state::<AppState>().dex.lock().unwrap().take() {
                    let _ = c.kill();
                }
            }
        });
}
