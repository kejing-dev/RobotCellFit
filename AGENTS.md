# Robot Cell Fit (机器人工位)

Puts a life-size UR5e on your real desk (or on the floor on an automatic stand) and shows at a glance what
it can reach, whether its full extension reaches the wall, and whether the preset poses collide. PICO OS 6,
Kotlin, PICO Spatial SDK 6.1.9, one Mixed Stage (Full Space, passthrough), package `io.embodit.robotcell`.

Design package (binding): `../spatial-app-factory/apps/robot-cell/design-package.json` (archetype D,
container STAGE_MIXED, window model single_panel_with_popup, 21 states S01 - S21, copy, rules). Mockup:
`../spatial-app-factory/apps/robot-cell/sample.html` (its `sample.sim.js` holds the same kinematics and
verdict rules). Skeleton: `../fire-plan` (which took it from `../spatial-ruler`; that project's `AGENTS.md`
holds the SDK findings this app relies on).

Built by the factory's build station on 2026-10-25 (v0.1.0, debug). Self-tested on emulator-5564 (AVD
Pico_Emulator_6_1_b) the same day: all 21 states captured, 30-round panel stress test passed. Not run on a headset.

## Structure

```
app/src/main/java/io/embodit/robotcell/
  Main.kt                      DefaultStage { RobotCellTheme { CellScreen() } }
  platform/                    SpatialApplication (launch), LaunchActivity, CellGraph (wiring), CellLog
    sense/PlaneTracker         plane detection -> PlaneSurface (Stage metres)            [from Spatial Ruler]
    tracking/HeadTracker       HMD pose, lost-tracking sign, block reason                [from Spatial Ruler]
    anchor/WorldAnchorGateway  world anchors create / load / remove                      [from Spatial Ruler]
    input/ControllerInput      controller ray, trigger, thumbstick                        [from Fire Plan]
    input/SystemPointer        what is known about the gaze + pinch pointer
  domain/model                 Vec3 / Quat4, PlaneSurface, Ur5e (DH forward kinematics, radii, presets),
                               CellModel (Robot, Pin, Mount, Pose, Gripper, Settings, StoredCell)
  domain/usecase               Flat (a plane as a 2D chart: convex hull, clipping, insets), ReachUseCase (desk share,
                               work disk, wall verdict and rings, pins, capsule collisions, clamps), PlaceRobotUseCase
                               (desk / floor placement, heading, constraints), HitSurface, ViewFrame, FacePanel, RestBody
  data/                        CellRepository (SharedPreferences JSON: anchor id + robot relative to it + pins + settings)
  ui/theme                     CellColors (the only colour literals), RobotCellTheme (PicoTheme, 16 roles), typography
  ui/cell                      CellViewModel (the whole state machine), CellUiState / CellEvent, CellScreen
  ui/cell/components           CellStage (SpatialView + panel pool), CellPanel (+ AimPanel, chips, tabs), MoreMenu,
                               Overlays (dialog, snackbar, coachmark, hit tag, plates), Common (glass, keys, type roles)
  ui/cell/scene                CellScene (ECS: one frame System; taps, drags, stick, pose easing, collisions, ghost, plates),
                               RobotView (procedural UR5e white model + ghost), CellViews (reach sphere, work disks / wall
                               rings, dead zone, pedestal, landing ring, pins, tapes), CellMeshes, PanelRig, PanelSpec,
                               Follower, PlaneColliders, SceneKit (materials, mesh factory)
  ui/cell/debug/CellProbe      seams for the debug receiver (no-ops in release)
app/src/debug/                 DebugCommandReceiver + DebugCommands (the QA hooks)
tools/make_icon.py             the 512 export and the legacy launcher PNG (PIL)
```

One direction of data: panel controls, scene callbacks (taps, drags, the stick, collisions, head ten times a
second, planes, tracking) and debug hooks -> `CellViewModel.onEvent` -> `CellUiState` -> `CellScene.render`
(idempotent diff of entities) and the panels. Per-frame work (pose easing, FK, capsule collisions, the aiming
ghost, thumbstick turning, plate placement, lazy follow) lives in `CellScene.onFrame` only.

## Kinematics and verdicts (domain, plain JVM)

- `Ur5e.frames(jointsDeg)`: standard DH (d1 0.1625, a2 -0.425, a3 -0.3922, d4 0.1333, d5 0.0997, d6 0.0996,
  alpha [90, 0, 0, 90, -90, 0] degrees), DH Z-up mapped to the robot's local Y-up as (x, z, -y). Zero pose
  flange in DH base coordinates: (-0.817, -0.233, 0.063). At J1 = 0 the arm reaches along local -X;
  `Robot.forwardOf(yaw)` / `Robot.yawFacing(dir)` turn that into a Stage heading.
- Presets (degrees): upright [0,-90,0,-90,0,0], tucked [0,-60,155,-15,-90,0], reach [0,0,0,0,0,0],
  pick [0,-75,100,-115,-90,0]. Switching eases in joint space over 0.8 s (scene).
- Reach radius R = 0.85 m + gripper (0 / 0.10 / 0.15 / 0.20), centre = shoulder = mount surface + 0.1625.
- Desk share: 2 cm grid over the desk's convex hull, counting points with horizontal distance to the shoulder
  <= sqrt(R^2 - h^2) and >= 0.15 m (dead zone); >= 60 % green, 30 - 59 % amber, below red.
- Wall: nearest WALL / DOOR / WINDOW plane near the shoulder; d < R -> amber "Wall in R - d" + orange ring on
  the wall; R <= d < R + 0.5 -> green "Wall d - R" + tape; farther -> "Wall > 50 cm"; no wall -> "Wall —".
- Pins: in reach when within R of the shoulder and outside the dead zone; at most 8.
- Collisions: seven capsules (base 0.075, shoulder 0.06, upper arm 0.055, forearm / wrists 0.045, tool 0.04)
  against walls as planes (room side = shoulder side) and every desk as a 4 cm slab; segments 0 and 1 skip the
  robot's own desk; the pedestal is never checked. Colliding parts turn red, a hit tag hangs 6 cm above the
  first contact, the name badge shows "Pose collides".

## What the room contains

- UR5e white model (`RobotView`): base cylinder 149 mm, five link cylinders between FK joint points, six dark
  joint drums on the joint axes with a blue ring each, a flange disc. Procedural, no asset. The ghost is the
  same model at 30 % while aiming.
- Reach sphere: ribbons on a unit sphere (latitude every 15 degrees, meridians every 30 degrees, 6 mm), cyan 30 %,
  scaled to R; menu switch hides it.
- Work disk on every desk the sphere meets: 64 clipped sectors (dead zone cut out on the mount desk), cyan 22 %,
  dashed edge. Orange ring on every wall the sphere meets (alert 22 % + solid edge).
- Dead-zone dashes (r 0.15) on the mount surface; floor pedestal 0.20 x 0.20 x desk height with hazard stripes;
  landing ring under the ghost (green on desk / floor, grey elsewhere); snap ring while the stick turns.
- Tapes: white tape from the sphere to the wall with a plate "x to wall" (within 50 cm); yellow tapes from the
  base to the two nearest desk edges while dragging, each with a plate; red "x short" plates above unreachable
  pins on the pins tab (nearest four).

## Interaction

- Place: with a desk known, the aiming ghost follows the controller ray (or the head's view line on the system
  pointer) and a pinch / tap on a TABLE places the robot inside the desk top; a tap on the FLOOR adds the
  pedestal (height = nearest desk within 1.5 m, else 0.75 m) and keeps the base 11 cm clear of every desk.
  Heading: reach direction towards the desk's centre. First placement shows the coachmark once.
- Move: press-and-hold on the base and drag; the hand's travel moves the base in the mount plane (right /
  forward of the head), clamped to the desk or off the desks; edge readings shown while dragging.
- Turn: the thumbstick X turns the robot about its vertical axis (dead zone 0.15, 60 degrees per second at
  full tilt), releasing snaps to the nearest 15 degree step within 5 degrees; the panel's two keys turn 15
  degrees per press. The cell panel carries the hint line "Turn with the stick · hold the base to move".
- Pose tab: four tiles; joints tab: J1 - J6 segments, a slider (-180 .. 180), -5 / +5, Zero (= upright);
  pins tab: tapping a desk drops a pin, tapping a pin removes it (only on this tab).
- Gripper cycles none / 10 / 15 / 20 cm (reach radius, pin verdicts and the tool capsule; no gripper model).
- Delete (undo via the snackbar for 6 s or the undo key, depth 20); More menu: reach sphere, language,
  units, Clear all (dialog), Exit. Tapping empty space only closes the menu.
- Too close: head within 0.25 m of any segment -> the robot at 40 %. Lost tracking: everything grey, panel
  controls disabled.
- Saving: a world anchor at the base; robot, pins and settings relative to it, saved 0.5 s after a change;
  on start the anchor is looked for (S16), 10 s -> "Last cell isn't here" with Clear (S18), found later -> restored.

## Panels (all SpatialUI inside PicoTheme; no Material)

Pooled attachment panels (`PanelSpec`), 1 dp = 0.8 mm at scale 1, the cell family at 0.75: `cellPanel`
400 x 400 (name row 48, chips 40, segments 40, content 136, hint 16, bottom row 48), `aimPanel` 400 x 120,
`menuPanel` 300 x 264 (above the cell panel), `dialogPanel` 400 x 208 (0.8 m ahead at 1.125), `snackPanel`
360 x 56 (head-locked 14 degrees up at 1.125), `coachPanel` 280 x 96 (30 cm above the base), `hitTag` 132 x 40,
`plateWall` / `plateEdge1` / `plateEdge2` 200 x 40, `platePin1..4` 120 x 40 (plates scale with distance for a
constant visual size). Dialogs and the snackbar are self-built (`AlertDialog` / `SnackbarHost` do nothing in a
Stage). Copy: `res/values/strings.xml` (en) and `res/values-zh`, read through `rs()` so the language switch
works without a restart. Fonts: Noto Sans + Noto Sans SC bundled (OFL, whole SC font 17 MB).

## QA hooks (debug build only)

```
adb -s <serial> shell "am broadcast -a io.embodit.robotcell.DEBUG -p io.embodit.robotcell --es cmd goto --es s S05"
adb -s <serial> shell "am broadcast -a io.embodit.robotcell.DEBUG -p io.embodit.robotcell --es cmd 'goto S05'"
```

| command | effect |
|---|---|
| `goto S01` … `goto S21` | straight to that designed state, scene set up for it (S05 places on the largest desk, S06 on the floor 0.9 m behind the desk centre, S07 moves to 0.78 m from the nearest wall, S08 freezes the reach easing half way, S09 = wall 0.6 + heading at the wall + reach, S11 drops 3 green + 1 red pins, S12 shows the drag readings, S19 forces lost tracking) |
| `place table\|floor [x z [yaw]]` | the robot at an offset (metres) from the first desk's centre, heading in degrees (`--es surface --ef x --ef z --ef yaw`) |
| `pose upright\|tucked\|reach\|pick [yaw] [freeze]` | a preset, optional heading, optional freeze share (`--es name --ef yaw --ef freeze`) |
| `wall d` | moves the robot so that the shoulder is d metres from the nearest wall (`--ef d`) |
| `pin x z` | a pin at an offset from the base on the mount desk (`--ef x --ef z`) |
| `drag on\|off`, `tab pose\|joints\|pins`, `menu`, `clear`, `clear_now`, `delete`, `undo`, `exit` | panel actions |
| `coach_reset`, `tracking lost\|ok`, `lang zh\|en\|system`, `units cm\|in`, `sphere on\|off` | settings and states |
| `ui <control>` | presses a panel control by name (`DebugCommands.kt` lists them) |
| `dump` | `DUMP {json}` (also files/dump.json): state name, robot, verdicts, pins, hits, planes, head |
| `hmd`, `bounds`, `planes`, `park`, `fps on\|off` | scene queries |

Screenshot list for QA (design `states[].qaCapture`): S01 (spinner), S02 (compact panel dim + snackbar "look
around"), S03 (ghost + green ring), S04 (floor-only ring, snackbar "look at the desk"), S05, S06, S07 (orange
ring + "Past wall"), S08 (frozen half way), S09 (red segments + hit tag), S10, S11, S12, S13, S14, S15, S16,
S17, S18, S19, S20, S21. Log tag `RobotCell`: `STATE Sxx` on every change, plus `PLANES`, `TAP`, `DRAG`,
`STICK`, `ROBOT placed|deleted|drag|turn|gripper`, `POSE`, `PIN`, `HIT`, `ANCHOR`, `RESTORE`, `SNACK`, `CMD`, `DUMP`.

## Build

```
# PowerShell (Git Bash fails on gradlew: CRLF)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug --no-daemon
adb -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk
adb -s <serial> shell monkey -p io.embodit.robotcell -c android.intent.category.LAUNCHER 1
```

Material gate: `grep -rEf ../spatial-app-factory/bin/material-grep.txt --include=*.kt --include=*.kts app/src`
must be empty.

## Deviations from the design package (recorded in the factory's state.json)

- Cell panel 376 -> 400 dp: one 16 dp hint line for the factory's default-interaction rule ("turn with the
  stick · hold the base to move").
- Pin "x short" plates: four pooled panels instead of eight (nearest unreachable pins first).
- UR5e: procedural white model (the design's own fallback), not the converted official meshes.
- The aiming ghost follows the controller ray, or the head's view line with the system pointer (the app never
  learns where the gaze pointer hovers); placement itself uses the real tap point.
- Noto Sans SC bundled whole (17 MB), no subsetting on this machine.

## Emulator self-test (2026-10-25, emulator-5564)

- Shots: `../spatial-app-factory/apps/robot-cell/build/shots/S01.png` ... `S21.png`, first screen and launcher
  icon (the system "Back to Home" dialog draws the app icon) in `build/`. Recipe: `tools/relaunch.sh [install]`
  (resets the emulator camera, then starts the app: the Stage origin is the camera pose at launch, and a camera
  moved before launch puts the planes and the head in different frames), then `SH=<dir> tools/qa_shot.sh Sxx`.
  The reset camera looks at the window-seat desk 2.2 m ahead; S15 must be captured within its 6 s snackbar.
- `tools/stress_panels.sh 30`: 30 rounds of menu open / close and the three tabs; process alive, no crash,
  glass panels intact afterwards (`build/stress-after-30.png`).
- Found and fixed on the emulator: the app was killed when the emulator ran out of memory (reboot fixed it,
  not an app bug); a restored robot did not adopt its desk when it came back before the planes (false
  "hits desk"); a new placement now cancels a pending restore and its "not here" snackbar; debug hooks
  (`goto S01/S02` show the aiming panel, S06 stands in view, S07/S09 face a real wall and S09 searches a heading
  whose reach pose hits it, every `goto` starts upright, S11 puts the red pin on another desk when the mount
  desk has no unreachable spot, coachmark pulled towards the viewer so the upright arm does not hide it).
- Emulator limits: the controller reports DEVICE_NOT_SUPPORTED in eye mode, so the thumbstick turn was not
  exercised; posted W/A/S/D/Q/E camera keys are unreliable (can carry the camera through a wall).

## QA round 1 rework (v0.1.1): start-up ANR

Every cold start raised "isn't responding": `CellScene.build` created 13 meshes back to back and each
`MeshResource` waits for the render thread, which draws 1 - 2 frames a second on the idle emulator. Now every
mesh of the build waits for a turn (`meshTurn()`) and the frame system hands turns out (`runMeshWork`): two per
frame while frames are under 40 ms (headset), one every 1.5 s while they are slow; work disks and wall rings are
rebuilt through the same keyed queue, and only when the shoulder, the radius or the planes they are cut from
changed. On the idle emulator the robot appears about 20 s after launch (S01 spinner meanwhile); `emu.sh fast on`
drains the queue at once. Log: `MESH <name> ms=<blocked ms>`.

## Not built / not verified

Headset-only: controller stick feel, pinch drag feel, the collision haptic (T12, not implemented), real desk
polygons, anchor restore accuracy, permissions.
