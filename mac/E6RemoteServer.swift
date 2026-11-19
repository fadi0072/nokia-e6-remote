// E6 Remote — Mac side.
// Receives commands from the Nokia E6 over Wi-Fi (TCP) and turns them into
// real mouse / keyboard events. Needs Accessibility permission.
//
// Build:  ./build-mac.sh      Run:  ./dist/e6remote-server [--port 5555] [--pin 1234]
//                                     [--invert-scroll] [--no-broadcast] [--dry-run]

import AppKit
import ApplicationServices
import CoreGraphics
import Foundation

// MARK: - Options

struct Options {
    var port: UInt16 = 5555
    var pin: String?
    var invertScroll = false
    var broadcast = true
    var dryRun = false
}

func parseOptions() -> Options {
    var o = Options()
    var args = CommandLine.arguments.dropFirst().makeIterator()
    while let a = args.next() {
        switch a {
        case "--port": if let v = args.next(), let p = UInt16(v) { o.port = p }
        case "--pin": o.pin = args.next()
        case "--invert-scroll": o.invertScroll = true
        case "--no-broadcast": o.broadcast = false
        case "--dry-run": o.dryRun = true
        case "-h", "--help":
            print("usage: e6remote-server [--port N] [--pin DIGITS] [--invert-scroll] [--no-broadcast] [--dry-run]")
            exit(0)
        default:
            print("unknown option \(a) (try --help)"); exit(2)
        }
    }
    return o
}

/// PIN is generated once and kept in ~/.e6remote/pin so the phone only needs it entered once.
func loadOrCreatePin() -> String {
    let dir = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent(".e6remote")
    let file = dir.appendingPathComponent("pin")
    if let s = try? String(contentsOf: file, encoding: .utf8) {
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        if !t.isEmpty { return t }
    }
    let pin = String(format: "%04d", Int.random(in: 0...9999))
    try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    try? pin.write(to: file, atomically: true, encoding: .utf8)
    return pin
}

// MARK: - Network interfaces

struct Iface { let name: String; let ip: String; let broadcast: String? }

func ipv4Interfaces() -> [Iface] {
    var result: [Iface] = []
    var ifap: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&ifap) == 0, let first = ifap else { return [] }
    defer { freeifaddrs(ifap) }
    func str(_ sa: UnsafeMutablePointer<sockaddr>) -> String {
        var buf = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        getnameinfo(sa, socklen_t(sa.pointee.sa_len), &buf, socklen_t(buf.count), nil, 0, NI_NUMERICHOST)
        return String(cString: buf)
    }
    for p in sequence(first: first, next: { $0.pointee.ifa_next }) {
        let i = p.pointee
        guard let sa = i.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET) else { continue }
        let flags = Int32(i.ifa_flags)
        guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0 else { continue }
        var bcast: String?
        if flags & IFF_BROADCAST != 0, let b = i.ifa_dstaddr { bcast = str(b) }
        result.append(Iface(name: String(cString: i.ifa_name), ip: str(sa), broadcast: bcast))
    }
    return result
}

// MARK: - Event injection

final class Injector {
    static let modCmd = 1, modCtrl = 2, modAlt = 4, modShift = 8

    private let src = CGEventSource(stateID: .hidSystemState)
    private let dryRun: Bool
    var invertScroll = false
    var speed = 5

    private var leftDown = false
    private var pos = CGPoint.zero
    private var lastMove = 0.0
    private var accX = 0.0, accY = 0.0, scrollAcc = 0.0
    private var lastClick = (time: 0.0, button: "", pos: CGPoint.zero, count: 0)

    init(dryRun: Bool) { self.dryRun = dryRun }

    private func now() -> Double { ProcessInfo.processInfo.systemUptime }

    private func post(_ e: CGEvent?) {
        guard !dryRun, let e = e else { return }
        e.post(tap: .cghidEventTap)
    }

    private func flags(_ mods: Int) -> CGEventFlags {
        var f: CGEventFlags = []
        if mods & Injector.modCmd != 0 { f.insert(.maskCommand) }
        if mods & Injector.modCtrl != 0 { f.insert(.maskControl) }
        if mods & Injector.modAlt != 0 { f.insert(.maskAlternate) }
        if mods & Injector.modShift != 0 { f.insert(.maskShift) }
        return f
    }

    /// Our idea of the cursor position; re-synced from the system when idle
    /// (so the real trackpad can be used in between).
    private func cursor() -> CGPoint {
        if now() - lastMove > 0.3 { pos = CGEvent(source: nil)?.location ?? pos }
        return pos
    }

    private func displays() -> [CGRect] {
        var n: UInt32 = 0
        CGGetActiveDisplayList(0, nil, &n)
        var ids = [CGDirectDisplayID](repeating: 0, count: Int(n))
        CGGetActiveDisplayList(n, &ids, &n)
        return ids.map { CGDisplayBounds($0) }
    }

    /// Keeps the pointer on a screen; lets it cross onto adjacent displays.
    private func clamp(_ p: CGPoint, from old: CGPoint) -> CGPoint {
        let ds = displays()
        if ds.contains(where: { $0.contains(p) }) { return p }
        let d = ds.first(where: { $0.contains(old) }) ?? ds.first ?? CGRect(x: 0, y: 0, width: 1440, height: 900)
        return CGPoint(x: min(max(p.x, d.minX), d.maxX - 1), y: min(max(p.y, d.minY), d.maxY - 1))
    }

    func move(_ dx: Int, _ dy: Int) {
        let v = hypot(Double(dx), Double(dy))
        // speed 1..10 (phone setting) with acceleration: slow = precise, fast = covers the screen.
        let gain = (Double(speed) / 5.0) * (0.7 + min(v, 40) / 12.0)
        accX += Double(dx) * gain
        accY += Double(dy) * gain
        let ix = accX.rounded(.towardZero), iy = accY.rounded(.towardZero)
        accX -= ix; accY -= iy
        if ix == 0 && iy == 0 { return }
        let old = cursor()
        pos = clamp(CGPoint(x: old.x + ix, y: old.y + iy), from: old)
        lastMove = now()
        let type: CGEventType = leftDown ? .leftMouseDragged : .mouseMoved
        let e = CGEvent(mouseEventSource: src, mouseType: type, mouseCursorPosition: pos, mouseButton: .left)
        e?.setIntegerValueField(.mouseEventDeltaX, value: Int64(ix))
        e?.setIntegerValueField(.mouseEventDeltaY, value: Int64(iy))
        post(e)
    }

    func click(_ button: String, mods: Int) {
        let p = cursor()
        let t = now()
        let same = lastClick.button == button && t - lastClick.time < NSEvent.doubleClickInterval
            && hypot(p.x - lastClick.pos.x, p.y - lastClick.pos.y) < 6
        let count = same ? lastClick.count + 1 : 1
        lastClick = (t, button, p, count)
        let right = button == "R"
        let (down, up, btn): (CGEventType, CGEventType, CGMouseButton) =
            right ? (.rightMouseDown, .rightMouseUp, .right) : (.leftMouseDown, .leftMouseUp, .left)
        for type in [down, up] {
            let e = CGEvent(mouseEventSource: src, mouseType: type, mouseCursorPosition: p, mouseButton: btn)
            e?.setIntegerValueField(.mouseEventClickState, value: Int64(count))
            e?.flags = flags(mods)
            post(e)
        }
    }

    func button(down: Bool) {
        if leftDown == down { return }
        leftDown = down
        let e = CGEvent(mouseEventSource: src, mouseType: down ? .leftMouseDown : .leftMouseUp,
                        mouseCursorPosition: cursor(), mouseButton: .left)
        e?.setIntegerValueField(.mouseEventClickState, value: 1)
        post(e)
    }

    func releaseAll() { if leftDown { button(down: false) } }

    func scroll(_ dy: Int) {
        // Finger down = content down (like a touchscreen). --invert-scroll flips it.
        scrollAcc += Double(dy) * 2.5 * (invertScroll ? -1 : 1)
        let px = scrollAcc.rounded(.towardZero)
        scrollAcc -= px
        if px == 0 { return }
        post(CGEvent(scrollWheelEvent2Source: src, units: .pixel, wheelCount: 1,
                     wheel1: Int32(px), wheel2: 0, wheel3: 0))
    }

    // Keyboard ---------------------------------------------------------

    private static let named: [String: CGKeyCode] = [
        "enter": 36, "tab": 48, "space": 49, "backspace": 51, "esc": 53, "delete": 117,
        "home": 115, "end": 119, "pageup": 116, "pagedown": 121,
        "left": 123, "right": 124, "down": 125, "up": 126,
    ]

    /// US-ANSI key positions, used when a modifier is held (shortcuts need key codes).
    private static let usKeys: [Character: (CGKeyCode, Bool)] = {
        var m: [Character: (CGKeyCode, Bool)] = [:]
        let plain: [(Character, CGKeyCode)] = [
            ("a", 0), ("s", 1), ("d", 2), ("f", 3), ("h", 4), ("g", 5), ("z", 6), ("x", 7), ("c", 8), ("v", 9),
            ("b", 11), ("q", 12), ("w", 13), ("e", 14), ("r", 15), ("y", 16), ("t", 17), ("1", 18), ("2", 19),
            ("3", 20), ("4", 21), ("6", 22), ("5", 23), ("=", 24), ("9", 25), ("7", 26), ("-", 27), ("8", 28),
            ("0", 29), ("]", 30), ("o", 31), ("u", 32), ("[", 33), ("i", 34), ("p", 35), ("l", 37), ("j", 38),
            ("'", 39), ("k", 40), (";", 41), ("\\", 42), (",", 43), ("/", 44), ("n", 45), ("m", 46), (".", 47),
            ("`", 50), (" ", 49),
        ]
        let shifted: [(Character, CGKeyCode)] = [
            ("!", 18), ("@", 19), ("#", 20), ("$", 21), ("^", 22), ("%", 23), ("+", 24), ("(", 25), ("&", 26),
            ("_", 27), ("*", 28), (")", 29), ("}", 30), ("{", 33), ("\"", 39), (":", 41), ("|", 42), ("<", 43),
            ("?", 44), (">", 47), ("~", 50),
        ]
        for (c, k) in plain { m[c] = (k, false) }
        for (c, k) in shifted { m[c] = (k, true) }
        for (c, k) in plain where c.isLetter { m[Character(c.uppercased())] = (k, true) }
        return m
    }()

    private static let modifierKeys: [(Int, CGKeyCode, CGEventFlags)] = [
        (modCtrl, 59, .maskControl), (modAlt, 58, .maskAlternate),
        (modShift, 56, .maskShift), (modCmd, 55, .maskCommand),
    ]

    /// Presses modifiers, taps the key, releases modifiers — like a real keyboard.
    private func tap(_ code: CGKeyCode, mods: Int) {
        var cur: CGEventFlags = []
        let held = Injector.modifierKeys.filter { mods & $0.0 != 0 }
        for (_, k, f) in held {
            cur.insert(f)
            let e = CGEvent(keyboardEventSource: src, virtualKey: k, keyDown: true); e?.flags = cur; post(e)
        }
        for down in [true, false] {
            let e = CGEvent(keyboardEventSource: src, virtualKey: code, keyDown: down); e?.flags = cur; post(e)
        }
        for (_, k, f) in held.reversed() {
            cur.remove(f)
            let e = CGEvent(keyboardEventSource: src, virtualKey: k, keyDown: false); e?.flags = cur; post(e)
        }
    }

    func key(_ name: String, mods: Int) {
        guard let code = Injector.named[name] else { log("unknown key \(name)"); return }
        tap(code, mods: mods)
    }

    func type(_ scalar: UInt32, mods: Int) {
        guard let u = UnicodeScalar(scalar) else { return }
        let ch = Character(u)
        let shortcutMods = mods & (Injector.modCmd | Injector.modCtrl | Injector.modAlt)
        if shortcutMods != 0, let (code, shift) = Injector.usKeys[ch] {
            tap(code, mods: mods | (shift ? Injector.modShift : 0))
            return
        }
        if mods & Injector.modShift != 0 && ch == " " { tap(49, mods: mods); return }
        // Plain text: send the actual character, independent of the Mac's keyboard layout.
        var utf16 = Array(String(ch).utf16)
        let code = Injector.usKeys[ch]?.0 ?? 0
        for down in [true, false] {
            let e = CGEvent(keyboardEventSource: src, virtualKey: code, keyDown: down)
            e?.keyboardSetUnicodeString(stringLength: utf16.count, unicodeString: &utf16)
            post(e)
        }
    }
}

// MARK: - Server

let opts = parseOptions()
let pin = opts.pin ?? loadOrCreatePin()
let injector = Injector(dryRun: opts.dryRun)
injector.invertScroll = opts.invertScroll
let injectQueue = DispatchQueue(label: "inject") // all events in order, one at a time

func log(_ s: String) {
    let f = DateFormatter(); f.dateFormat = "HH:mm:ss"
    print("[\(f.string(from: Date()))] \(s)")
    fflush(stdout)
}

func handle(_ line: String, verbose: Bool) {
    let p = line.split(separator: " ").map(String.init)
    guard let cmd = p.first else { return }
    func int(_ i: Int) -> Int { i < p.count ? Int(p[i]) ?? 0 : 0 }
    if verbose && cmd != "P" { log("  \(line)") }
    injectQueue.async {
        switch cmd {
        case "M": injector.move(int(1), int(2))
        case "S": injector.scroll(int(1))
        case "C": injector.click(p.count > 1 ? p[1] : "L", mods: int(2))
        case "D": injector.button(down: true)
        case "U": injector.button(down: false)
        case "T": injector.type(UInt32(max(0, int(1))), mods: int(2))
        case "K": injector.key(p.count > 1 ? p[1] : "", mods: int(2))
        case "P": break
        default: log("unknown command: \(line)")
        }
    }
}

func peerName(_ fd: Int32) -> String {
    var addr = sockaddr_in(), len = socklen_t(MemoryLayout<sockaddr_in>.size)
    _ = withUnsafeMutablePointer(to: &addr) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getpeername(fd, $0, &len) }
    }
    return String(cString: inet_ntoa(addr.sin_addr))
}

func serveClient(_ fd: Int32) {
    let peer = peerName(fd)
    var one: Int32 = 1
    setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, socklen_t(MemoryLayout<Int32>.size))
    var tv = timeval(tv_sec: 15, tv_usec: 0) // phone pings every 3 s; drop dead links
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
    func reply(_ s: String) { _ = s.withCString { send(fd, $0, strlen($0), 0) } }

    var authed = false
    var pending = [UInt8]()
    var buf = [UInt8](repeating: 0, count: 4096)
    loop: while true {
        let n = recv(fd, &buf, buf.count, 0)
        if n <= 0 { break }
        pending.append(contentsOf: buf[0..<n])
        while let nl = pending.firstIndex(of: 10) {
            let line = String(decoding: pending[..<nl], as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
            pending.removeSubrange(...nl)
            if line.isEmpty { continue }
            if !authed {
                let p = line.split(separator: " ")
                if p.count >= 2 && p[0] == "H" && p[1] == pin {
                    authed = true
                    let speed = p.count > 2 ? Int(p[2]) ?? 5 : 5
                    injectQueue.async { injector.speed = min(max(speed, 1), 10) }
                    reply("OK\n")
                    log("Phone connected from \(peer) (speed \(speed))")
                } else {
                    reply("NO\n")
                    log("Rejected \(peer): wrong PIN")
                    break loop
                }
            } else {
                handle(line, verbose: opts.dryRun)
            }
        }
        if pending.count > 8192 { break } // garbage, not our phone
    }
    close(fd)
    injectQueue.async { injector.releaseAll() }
    if authed { log("Phone disconnected (\(peer))") }
}

func startServer() {
    let fd = socket(AF_INET, SOCK_STREAM, 0)
    var one: Int32 = 1
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, socklen_t(MemoryLayout<Int32>.size))
    var addr = sockaddr_in()
    addr.sin_family = sa_family_t(AF_INET)
    addr.sin_port = opts.port.bigEndian
    addr.sin_addr.s_addr = INADDR_ANY
    let ok = withUnsafePointer(to: &addr) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
    }
    guard ok == 0, listen(fd, 4) == 0 else {
        print("Could not listen on port \(opts.port): \(String(cString: strerror(errno)))")
        exit(1)
    }
    Thread {
        while true {
            let c = accept(fd, nil, nil)
            if c >= 0 { Thread { serveClient(c) }.start() }
        }
    }.start()
}

/// Announces "E6REMOTE <port>" on UDP 5556 so the phone's "Find Mac" can locate us.
func startBroadcast() {
    let fd = socket(AF_INET, SOCK_DGRAM, 0)
    var one: Int32 = 1
    setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &one, socklen_t(MemoryLayout<Int32>.size))
    let msg = "E6REMOTE \(opts.port)\n"
    Thread {
        while true {
            var targets = Set(ipv4Interfaces().compactMap { $0.broadcast })
            targets.insert("255.255.255.255")
            for t in targets {
                var a = sockaddr_in()
                a.sin_family = sa_family_t(AF_INET)
                a.sin_port = UInt16(5556).bigEndian
                inet_pton(AF_INET, t, &a.sin_addr)
                _ = msg.withCString { m in
                    withUnsafePointer(to: &a) {
                        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                            sendto(fd, m, strlen(m), 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                        }
                    }
                }
            }
            Thread.sleep(forTimeInterval: 2)
        }
    }.start()
}

// MARK: - Main

signal(SIGPIPE, SIG_IGN)
setvbuf(stdout, nil, _IOLBF, 0)

let trusted = AXIsProcessTrustedWithOptions(
    [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary)

print("""

  E6 Remote server
  ----------------
""")
for i in ipv4Interfaces() { print("  Mac IP:  \(i.ip)   (\(i.name))") }
print("""
  Port:    \(opts.port)
  PIN:     \(pin)

""")
if opts.dryRun {
    print("  DRY RUN: commands are printed, no real mouse/keyboard events are sent.\n")
} else if !trusted {
    print("""
  !! Accessibility permission is missing, so mouse/keyboard events will be ignored.
     Open System Settings > Privacy & Security > Accessibility and enable the app
     you started this from (Terminal / iTerm / Visual Studio Code), then restart the server.

""")
}

startServer()
if opts.broadcast { startBroadcast() }
log("Waiting for the phone on port \(opts.port)... (Ctrl+C to quit)")
dispatchMain()
