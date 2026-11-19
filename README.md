# E6 Remote

Use a Nokia E6 (Symbian^3 / Anna / Belle) as a wireless mouse and keyboard for a Mac.
The phone and the Mac talk over Wi-Fi.

```
Nokia E6  (Java ME app: touchpad + QWERTY)  --Wi-Fi/TCP 5555-->  Mac  (e6remote-server -> mouse & keyboard events)
```

🎬 **Demo video:** https://vimeo.com/1248412565

## Tech stack

| Part | Technology |
|---|---|
| Phone app | **Java ME** (CLDC 1.1 / MIDP 2.0), compiled to Java 1.3 bytecode with JDK 8 (Azul Zulu) |
| Phone UI | MIDP full-screen `Canvas` with touch and key events, `Form`/`TextBox` for settings and text entry, RMS `RecordStore` to save settings |
| Preverification & packaging | ProGuard (`-microedition`), JAR + JAD |
| Mac server | **Swift**, with CoreGraphics `CGEvent` for mouse and keyboard input, Accessibility API for the permission check, and AppKit |
| Networking | Plain TCP sockets with a simple text-line protocol, PIN authentication, and UDP broadcast so the phone can find the Mac |
| Build | Bash scripts and `swiftc`. No IDE or admin rights needed |
| Tested on | Nokia E6 (Symbian Anna/Belle) → MacBook Air M4 |

Other Nokia touchscreen Symbian phones (N8, E7, C7, …) should also work but haven't been tested.

## 1. Build

```bash
./setup.sh          # once: downloads JDK 8, Java ME API stubs and ProGuard into ./tools (no admin needed)
./build-phone.sh    # -> dist/E6Remote.jar + dist/E6Remote.jad
./build-mac.sh      # -> dist/e6remote-server
```

## 2. Run the server on the Mac

```bash
./dist/e6remote-server
```

The server prints the Mac's IP address, the port, and a 4-digit PIN. The PIN is created on first run and saved in `~/.e6remote/pin`.

The first time you run it, macOS asks for **Accessibility** permission. Turn it on for the app you started the server from (Terminal, iTerm or Visual Studio Code) in
System Settings → Privacy & Security → Accessibility. Then restart the server. Without this permission, events are silently ignored.

Options: `--port N`, `--pin 1234`, `--invert-scroll`, `--no-broadcast`, `--dry-run` (prints commands instead of moving the mouse).

## 3. Install the app on the E6

Pick one of these:

- **Over Wi-Fi:** run `./serve-phone.sh`. On the E6, open the Web browser and go to the URL it prints, such as `http://192.168.x.x:8000/E6Remote.jad`, and accept the install.
- **USB:** connect the E6 in *Mass storage* mode and copy `dist/E6Remote.jar` to the phone. On the phone, open it in File manager to install.

The app isn't signed, so Symbian asks whether it may use the network. Answer yes and pick your **WLAN** access point. To stop being asked every time, go to Menu → Applications → E6 Remote → *App. settings* → Network access → *Ask first time only*.

## 4. Connect

1. Open **E6 Remote**. The Setup screen appears the first time.
2. Choose **Find Mac** to detect the server automatically, or type the Mac's IP.
3. Enter the PIN and choose **Save**.

The dot in the top bar turns green when the phone is connected.

## Using it

| On the phone | On the Mac |
|---|---|
| Slide on the touchpad | Move the pointer (faster swipes accelerate) |
| Tap the touchpad | Left click (two quick taps = double click) |
| Hold on the touchpad (0.7 s) | Right click |
| Slide on the right-hand strip | Scroll |
| **L** / **R** buttons | Left / right click |
| **Drag** button | Hold the left button down: slide to drag, then tap the pad or Drag to drop |
| **Cmd Ctrl Alt Shift** | Tap once: applies to the next key or click (blue). Tap twice: locked (orange). Tap again: off |
| QWERTY keys | Typing (any character the phone can produce, including Fn/Sym symbols) |
| Navi-key arrows / centre | Arrow keys / Enter |
| Backspace, Enter | Backspace, Enter |
| **Esc**, **Tab** buttons | Esc, Tab |
| **Text** (top bar) | Opens the phone's full text editor (predictive text, symbols) and sends the whole text |
| **Setup** (top bar) | IP, port, PIN, pointer speed, Exit |

Shortcuts work by tapping the modifier, then the key. For example: **Cmd** then **c** copies, **Cmd** then **Tab** switches apps, and **Cmd** then **Space** opens Spotlight.

You can bake the Mac's address into the installer by adding `E6Remote-Host: 192.168.x.x` and `E6Remote-Pin: 1234` lines to `dist/E6Remote.jad`.

## Troubleshooting

- **Find Mac finds nothing:** make sure both devices are on the same Wi-Fi and that the network doesn't isolate clients from each other (common on guest networks). Typing the IP works without discovery.
- **The phone connects but nothing happens on the Mac:** Accessibility permission is missing (see step 2).
- **Scrolling goes the wrong way:** restart the server with `--invert-scroll`.
- **macOS asks about "local network" access:** allow it. The server needs it to talk to the phone.

## Layout

- `phone/src/`: Java ME app (CLDC 1.1 / MIDP 2.0). `Link.java` handles the network, `PadCanvas.java` the UI and input.
- `mac/E6RemoteServer.swift`: the server. It uses CoreGraphics to create mouse and keyboard events.
- Protocol: one text line per command, such as `M dx dy`, `C L mods`, `T unicode mods` or `K name mods`. See the comment in `Link.java`.

## License

MIT. See [LICENSE](LICENSE).
