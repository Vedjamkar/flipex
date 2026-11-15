// FlipEx desktop window: polls the Rust side for phones and drives DeX through it.
const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;
const $ = (id) => document.getElementById(id);

let current = null;      // last status from Rust
let qrTimer = null;

function toast(msg, ms = 4000) {
  const t = $("toast");
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => (t.hidden = true), ms);
}

function selected() {
  const ready = (current?.devices || []).filter((d) => d.state === "device");
  return ready.find((d) => d.serial === $("device").value) || ready[0] || null;
}

function render(s) {
  current = s;
  const ready = s.devices.filter((d) => d.state === "device");
  const pending = s.devices.find((d) => d.state === "unauthorized");

  // Device picker only when more than one phone is connected.
  const sel = $("device");
  const keep = sel.value;
  sel.innerHTML = ready.map((d) => `<option value="${d.serial}">${d.model || d.serial}${d.wifi ? " (Wi-Fi)" : " (USB)"}</option>`).join("");
  if (ready.some((d) => d.serial === keep)) sel.value = keep;
  sel.hidden = ready.length < 2;

  const dev = selected();
  const dot = $("dot");
  dot.className = "dot";
  if (s.dex_running) {
    dot.classList.add("green");
    $("phone-title").textContent = "DeX is on";
    $("phone-sub").textContent = `${dev?.model || "Phone"} · close the DeX window or tap Stop`;
  } else if (dev) {
    dot.classList.add("green");
    $("phone-title").textContent = dev.model || "Phone connected";
    $("phone-sub").textContent = dev.wifi ? "Connected over Wi-Fi" : "Connected over USB";
  } else if (pending) {
    dot.classList.add("amber");
    $("phone-title").textContent = "Allow USB debugging";
    $("phone-sub").textContent = "Unlock the phone and tap Allow on the prompt";
  } else {
    $("phone-title").textContent = "No phone found";
    $("phone-sub").textContent = s.nearby.length
      ? "Your phone is on Wi-Fi: tap Connect below"
      : "Plug it in with USB (debugging on), or pair over Wi-Fi below";
  }

  const btn = $("dex");
  btn.disabled = !dev && !s.dex_running;
  btn.textContent = s.dex_running ? "Stop DeX" : "Start DeX";
  btn.classList.toggle("stop", s.dex_running);

  $("nearby").innerHTML = s.nearby
    .map((n) => {
      const cut = n.lastIndexOf(" "); // names can contain spaces ("adb-XYZ (2)")
      const name = n.slice(0, cut), addr = n.slice(cut + 1);
      return `<button class="item link" data-addr="${addr}">Connect ${name.replace(/^adb-/, "").replace(/-.*$/, "")} <span class="sub">${addr}</span></button>`;
    })
    .join("");

  const t = s.tools;
  $("tools").textContent = !t.adb || !t.scrcpy
    ? "scrcpy or adb is missing from this build."
    : t.bundled ? "Uses the scrcpy and adb inside FlipEx." : "Uses the scrcpy and adb installed on this computer.";
}

async function refresh() {
  try {
    render(await invoke("status"));
  } catch (e) {
    $("phone-title").textContent = "Something went wrong";
    $("phone-sub").textContent = String(e);
  }
}

async function run(label, fn) {
  try {
    const msg = await fn();
    if (msg) toast(msg);
  } catch (e) {
    toast(`${label}: ${e}`, 7000);
  }
  refresh();
}

$("dex").onclick = () => {
  if (current?.dex_running) return run("Stop", () => invoke("stop_dex"));
  const dev = selected();
  if (!dev) return;
  run("Start DeX", () => invoke("start_dex", { serial: dev.serial, size: $("size").value, dark: $("dark").checked }));
};

$("nearby").onclick = (e) => {
  const b = e.target.closest("[data-addr]");
  if (b) run("Connect", () => invoke("connect", { address: b.dataset.addr }));
};

$("shizuku").onclick = () => {
  const dev = selected();
  if (!dev) return toast("Connect the phone first.");
  run("Shizuku", () => invoke("start_shizuku", { serial: dev.serial }));
};

$("restore").onclick = () => {
  const dev = selected();
  if (!dev) return toast("Connect the phone first.");
  if (confirm("Put Android's desktop settings on the phone back to stock?")) {
    run("Restore", () => invoke("restore", { serial: dev.serial }));
  }
};

// ---- pairing

async function startQr() {
  try {
    $("qr").innerHTML = await invoke("qr_start");
    $("qr-state").textContent = "Waiting for the phone…";
    clearInterval(qrTimer);
    qrTimer = setInterval(async () => {
      try {
        const r = await invoke("qr_poll");
        if (r === "paired") {
          clearInterval(qrTimer);
          $("qr-state").textContent = "Paired. Connecting…";
          toast("Paired with your phone.");
          setTimeout(() => ($("pair").hidden = true), 1500);
        }
      } catch (e) {
        clearInterval(qrTimer);
        $("qr-state").textContent = `Pairing failed: ${e}`;
      }
    }, 1500);
  } catch (e) {
    toast(`QR code: ${e}`);
  }
}

$("pair-open").onclick = () => {
  const p = $("pair");
  p.hidden = !p.hidden;
  if (p.hidden) clearInterval(qrTimer);
  else startQr();
};

document.querySelectorAll(".tab").forEach((tab) => {
  tab.onclick = () => {
    document.querySelectorAll(".tab").forEach((t) => t.classList.toggle("on", t === tab));
    $("tab-qr").hidden = tab.dataset.tab !== "qr";
    $("tab-code").hidden = tab.dataset.tab !== "code";
    if (tab.dataset.tab === "qr") startQr();
    else clearInterval(qrTimer);
  };
});

$("pair-go").onclick = () =>
  run("Pair", async () => {
    const out = await invoke("pair", { address: $("pair-addr").value, code: $("pair-code").value });
    if (!/successfully paired/i.test(out)) throw out.trim();
    $("pair").hidden = true;
    return "Paired with your phone.";
  });

listen("dex-ended", (e) => {
  const tail = (e.payload || "").trim().split("\n").pop();
  if (tail && /error|fail|could not/i.test(tail)) toast(`DeX window closed: ${tail}`, 8000);
  refresh();
});

refresh();
setInterval(refresh, 2500);
