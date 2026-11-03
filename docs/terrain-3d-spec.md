# 3D terrain drive view: implementation spec

A design, not built. Written 2026-11-03 on `clean/terrain-3d` (off `clean/map-smooth-follow`). It names the code as it stands on that branch and its siblings: `clean/map-dark` (`MapDarkContrast`), `clean/map-3d-buildings` (`MapBuildings3d`, `MapTileOptions`), `clean/gps-elevation` (`ElevationRecorder`, `ElevationTrack`) and `clean/emu-map-override` (`debug.dash.emumap`).

**The idea in one line:** draw the flat 2D map with MapLibre, then drape it as a picture over a low-poly terrain mesh built from elevation tiles, and look at it from a chase camera in a GL view of our own.

**Why it has to be done this way:** the unit has a Mali-450, which runs OpenGL ES 2.0 only. MapLibre Android 10.3.7 is the last release that runs on ES 2.0, and it has no 3D terrain: there is no terrain code in the AAR or in `libmapbox-gl.so`. It has `RasterDemSource` and `HillshadeLayer`, which only shade a flat map. Newer MapLibre releases have terrain but need ES 3. So the relief has to come from our own renderer, with MapLibre used only to paint the ground.

**Status of facts.** Facts marked *verified* were checked against the 10.3.7 AAR (`javap` on `classes.jar`, and a strings search of `jni/armeabi-v7a/libmapbox-gl.so`). Facts marked *unverified* must be confirmed in the Phase 0 spike before code depends on them.

## 1. What the driver gets

### Goals
- A **3D drive view**: the road ahead and the hills around it in real relief, seen from behind and above the car, with the map's roads, water, land use and labels draped over the ground.
- It runs smoothly on the unit: a 30 fps target, with no hitches when the map picture is refreshed.
- It works on the coast of Montenegro, where the road climbs from the sea at Bečići to about 1000 m within 3 km and Lovćen stands at 1749 m. This is the area every test fixture uses.

### Non-goals
- **Not a replacement for the interactive map.** Search, tap-to-route, the route panel, guidance, zoom and pan stay in the MapLibre tile (`MapLibrePanel`). The 3D view has no gestures apart from tap-to-leave (and possibly pinch for distance later).
- **No hillshade-only or colour-only "3D".** `HillshadeLayer` on the flat map is mentioned once below as a fallback, and is not part of this feature.
- No 3D buildings in this view (`MapBuildings3d` belongs to the 2D tile), no satellite imagery, no labels drawn as billboards (a possible later item), and no turn-by-turn UI of its own. Guidance banners stay where they are.
- Not on the Canvas wallpaper map or the cluster map in v1.

### Where it lives
| Option | Verdict |
|---|---|
| **A mode of the map tile** (a button on the tile switches 2D ⇄ 3D) | **Chosen.** One map per tile. The 2D map keeps its state (route, search, zoom) underneath, and there is no new tile type to explain. |
| A new tile design ("Drive 3D") | Rejected for v1. It would be a second map tile beside the first, with two MapLibre maps and the 3D view running at once on a Mali-450. |
| Full-screen page | Later. It is the same composable at a larger size (1280×720, about 1.5× the pixels of a large tile), so it waits for Phase 0's fill-rate numbers. |

- **Default off.** A new switch, "3D drive view", sits in the map tile's panel in edit mode (`TileOptions.kt` → `MapTileToggles`, next to "3D buildings"). It is stored in `MapTileOptions` (`map_tile` prefs, key `drive_3d`, default `false`), and the setup backup carries it like the rest.
- **Entering:** with the switch on, the map tile shows one more round `MapButton` (a "3D" or `ViewInAr` icon) in the column with zoom and recenter. Tapping it shows the 3D view over the 2D map. The choice is remembered (`map_tile` key `drive_3d_shown`), so the tile reopens in 3D after an ACC-off restart.
- **Leaving:** the same button (now "2D") or a tap anywhere on the 3D view. Opening search, picking a destination, or any guidance prompt that needs a tap also brings back 2D, because those flows live in the 2D map.
- **What the 2D map does meanwhile:** it stays composed and is capped at `HIDDEN_FPS` (10), exactly as on a page off screen. It is **not** paused: a paused tile map came back black (see the comment at `LaunchedEffect(pageActive, still)` in `MapLibrePanel`).
- **No GPS yet:** the 3D view opens at `LocationFeed.location.value` (the last fix), still, with a "Waiting for GPS" chip. With no position at all it shows the chip over the sky colour and draws nothing else.
- **No network:** terrain comes from the disk cache, and the map picture comes from MapLibre's own tile cache (the snapshot uses the same file source as the 2D map). Missing DEM tiles are drawn flat at the height of the nearest known sample, with a "No terrain data here" chip. If no map picture can be made, the ground is drawn in a plain land colour with lighting, which still shows the relief, plus a "Map offline" chip.
- **Driving into a tunnel**, or any GPS gap: the camera holds still, as `SmoothFollow` already does (`GAP_SNAP_MS`). There is no extrapolation.

## 2. Architecture

New code goes in `com.openauto.dash.terrain` (the vehicle bus set the precedent of a subpackage, `com.openauto.dash.vehicle`). Everything marked *pure* is plain Kotlin with no Android or GL, and is unit-tested on the JVM.

| File / class | Kind | Responsibility |
|---|---|---|
| `TileMath.kt` (`TileMath`) | pure | Converts lat/lon ⇄ XYZ tile ⇄ Web Mercator metres, gives tile bounds and the metres per pixel at a latitude and zoom. |
| `Terrarium.kt` (`Terrarium`) | pure | Turns RGB into metres: `IntArray` ARGB pixels → `FloatArray` heights. |
| `DemTile.kt` (`DemTile`, `DemKey`) | pure | One decoded tile: `z/x/y`, 256×256 `FloatArray`, min/max height. Immutable. |
| `DemTileCache.kt` (`DemTileCache`) | Android, IO | Fetches through `Http.client`, keeps a disk LRU and a memory LRU, decodes with `BitmapFactory`, publishes `StateFlow<DemSet>`. |
| `DemSampler.kt` (`DemSampler`) | pure | Bilinear height at any lat/lon or local metre position across tile edges, built over an immutable `DemSet`. |
| `LocalFrame.kt` (`LocalFrame`) | pure | Holds the anchor (lat/lon in doubles) and converts lat/lon ⇄ float metres (x east, y north, z up). |
| `TerrainMesh.kt` (`TerrainMeshBuilder`, `TerrainMeshData`) | pure | Builds the grid of vertices (position + normal) and the `ShortArray` indices for a frame and extent. |
| `MapTextureSource.kt` (interface) | — | `request(spec: TextureSpec)` → later a `MapPicture(bitmap or GL texture, georef)`. |
| `SnapshotTextureSource.kt` | Android, main | Option (a) in section 5: one reused `MapSnapshotter`. |
| `TextureSpec`, `Georef` (in `MapTexture.kt`) | pure | What to render (centre, bearing, zoom, size, pixel ratio), and the affine world-metre → UV mapping of the result. |
| `TextureRefreshPolicy.kt` | pure | Decides when a new near or far picture is due. |
| `ChaseCamera.kt` | pure | Gives eye and target from the drawn car pose, speed and terrain heights, plus smoothing and clearance. |
| `TerrainQuality.kt` | pure | The tiers (section 8) and the frame-time watchdog that steps down. |
| `TerrainRenderThread.kt` | Android, GL | Its own EGL ES 2.0 context on the `TextureView`'s `SurfaceTexture`, the 30 fps loop, pause and resume. Modelled on MapLibre's `TextureViewRenderThread` and on `Car3D`'s `Stage`. |
| `TerrainRenderer.kt` | GL | VBOs, textures, shaders and draw calls; owns all GL objects. |
| `TerrainShaders.kt` | — | The GLSL ES 1.00 sources. |
| `TerrainDrive.kt` (`TerrainDrive`) | Android | The coordinator: subscribes to `LocationFeed`, feeds the GL thread, asks `DemTileCache` for the area, runs the refresh policy and calls the texture source. Lives while the view is shown. |
| `TerrainDriveView.kt` | Compose | `@Composable TerrainDriveView(modifier, light: Boolean, onExit: () -> Unit)`: an `AndroidView` of a `TextureView`, lifecycle, `LocalPageActive`, and the overlays (status chip, attribution). |

**Changes to existing files:**
- `MapTileOptions.kt` gets `drive3d` and `drive3dShown` with the same load/set pattern as `buildings3d`.
- `TileOptions.kt` gets one more `SettingsToggle` in `MapTileToggles`.
- `MapLibrePanel.kt` gets the mode button, mounts `TerrainDriveView` over the `MapView`, and caps the 2D map at `HIDDEN_FPS` while 3D shows.
- `MapServers.kt` gets a `terrain` URL template in `Servers`, defaulting to AWS Terrarium, so that heavy users can point it at a mirror like the style URLs.
- `MapDarkContrast.kt` gets a JSON form of the same adjustments (section 5.4).
- New strings.

### Data flow and threads

```
LocationFeed.location (StateFlow, main)
  └─ TerrainDrive (main): ── fix ─────────────────▶ GL thread: FollowTrack.feed → ChaseCamera
        │                                                     ▲
        ├─ area needed ─▶ DemTileCache (IO: HTTP + disk; Default: decode)
        │                    └─ DemSet ─▶ Default: TerrainMeshBuilder ─▶ GL thread: VBO swap
        └─ TextureRefreshPolicy ─▶ SnapshotTextureSource (main → MapLibre native thread)
                                        └─ Bitmap (main) ─▶ GL thread: upload into back texture, swap
```

| Thread | Owns |
|---|---|
| Main | Compose, `TerrainDrive`, every `MapSnapshotter` call (it has a private `checkThread()`, *verified* present; assumed to mean the main looper, *unverified*), collecting `LocationFeed`. |
| GL (`TerrainRenderThread`) | The EGL context, all GL objects, its own `FollowTrack` (that class says "Main thread only", so the GL thread gets its own instance, fed from a `ConcurrentLinkedQueue` of fixes), `ChaseCamera` state, and the frame loop. |
| `Dispatchers.IO` | DEM HTTP and disk reads and writes. |
| `Dispatchers.Default` | PNG decode → heights, mesh build, normals, and the RGBA → RGB565 conversion of map pictures. |
| MapLibre native | The snapshotter's headless renderer, on its own thread with its own EGL pbuffer context. `HeadlessBackend` and `eglCreatePbufferSurface` are present in the `.so` (*verified*). |

Hand-offs are immutable objects (`DemSet`, `TerrainMeshData`, `MapPicture`) posted to the GL thread's queue. The GL thread never blocks on IO.

**Why a `TextureView` and not a `GLSurfaceView`:** the map tile sits in Compose pagers that slide. A `SurfaceView` punched a hole through the page before (perf review 2026-10-22, item 2), and `MapLibrePanel` uses `textureMode(true)` for the same reason. The cost is one more layer for HWUI's RenderThread to composite. That cost already exists for the 2D map, and the 2D map drops to 10 fps while 3D shows.

## 3. Elevation data (DEM)

### Source: AWS Terrain Tiles (Terrarium)
- URL: `https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png`. It needs no key, is public on the AWS Open Data registry, uses XYZ (slippy map) numbering, and has 256×256 PNG tiles at z0–15.
- Decode: `h = R * 256 + G + B / 256 - 32768` metres. In `Terrarium.decode(argb: IntArray): FloatArray`: `((p shr 16 and 0xFF) * 256f) + (p shr 8 and 0xFF) + (p and 0xFF) / 256f - 32768f`.
- `BitmapFactory` options: `inPreferredConfig = ARGB_8888`, `inPremultiplied = false` (the tiles are opaque, but this is cheap insurance), and `inScaled = false`. Read the pixels with `getPixels`. The JVM tests decode the same files with `javax.imageio` into the same `IntArray`, so `Terrarium` is tested without Android.
- Over Montenegro the data is a blend of SRTM and EU-DEM, at about 30 m. These are surface models in places: forest canopy and some bridges show up, and tunnels don't. See the puck height rules in section 6.

### Optional source: Mapterhorn (WebP)
- Mapterhorn publishes Terrarium-encoded WebP tiles built from newer sources (Copernicus GLO-30 and national lidar where it is open), so it is better in places. The URL template (`https://tiles.mapterhorn.com/{z}/{x}/{y}.webp`) and the tile size (512 px?) are both *unverified*.
- `BitmapFactory` decodes lossless WebP from API 18, so API 27 should be fine. **This is unverified on this unit:** decode one tile in Phase 0 and compare its heights against the AWS tile at 10 sample points.
- It **requires attribution** (section 12). It is offered as an alternative `MapServers.terrain` value, not the default.

### Zoom and area
At Budva (latitude 42.29°, cos = 0.740), a z12 tile is 40 075 km × 0.740 / 4096 = **7.24 km** wide, which gives **28.3 m per pixel**. That matches the 30 m data. z11 (56.6 m) is too coarse for the near ground, and z13 (14 m) only interpolates the same 30 m data, at 4× the tiles.

- **Fetch zoom z12**, fixed. A per-tier setting is not worth its complexity.
- **Area:** every z12 tile that touches a square of 2 × `extent` around the mesh anchor. For a 4 km mesh (the default tier) that is 2×2 to 3×3 tiles. Prefetch the next tiles 2 km ahead along the course.
- **Size:** about 40–150 KB per z12 tile over land (sea tiles are tiny). This is *unverified*: measure 9 tiles of the fixture. A 100 km drive along the coast touches about 40–60 tiles, which is **3–8 MB of mobile data**. All of Montenegro (about 14 000 km², about 300 z12 tiles plus a border) is **20–40 MB**, so an optional "keep terrain for this area offline" prefetch is realistic (Phase 2).

### Cache
- **Disk:** `cacheDir/terrain/{source-hash}/{z}/{x}/{y}.png`, stored as the raw downloaded bytes, never re-encoded. LRU by access time with a cap of 200 MB (enough for the country several times over), trimmed on start. Entries never expire: elevation data doesn't change. The source hash keys the folder, so switching sources never mixes data.
- **Memory:** an LRU of decoded `DemTile`s, each holding 256² floats (256 KB). Up to 16 tiles = 4 MB.
- **Network policy:** the fetch runs only while the 3D view is shown (or during an explicit prefetch). At most 2 run at once, through `Http.client.newBuilder()` with its own timeouts, the way `DashAssistant` and `GeminiClient` do. A failure backs off 30 s per tile. The fetcher never retries in a loop on a dead network.
- **Offline:** cached tiles only. A missing tile makes `DemSampler` return the height of the nearest known sample. If no tile is known, the height is 0 and the chip in section 1 shows.

## 4. Mesh

### Frame
- **`LocalFrame`:** the anchor is a lat/lon held in doubles. A point's local position is `(mercator(p) - mercator(anchor)) * cos(anchorLat)` → x east, y north (in metres), and z up = DEM height × exaggeration. Doubles are used up to the subtraction and floats after it. Within ±5 km the scale error of the shortcut is under 0.1 % (about 4 m at the far edge, far inside the fog), and float precision at 5 km is 0.5 mm.
- **The anchor snaps to the DEM grid** (multiples of the vertex spacing in Mercator metres). After a re-centre every vertex then sits at the same world position as before, so the swap is invisible. Only the far edge changes, and the far edge is in fog.
- **Re-centre** when the car is more than `extent / 4` from the anchor (1 km in the default tier). The new anchor is the car's position plus `extent / 6` along its course, because the camera looks ahead. The build runs on `Default`. The GL thread swaps the VBO, then re-expresses the car, camera, route and texture georefs in the new frame in the same frame. The coordinator owns one `LocalFrame` per mesh generation and hands it to the GL thread with the mesh.

### Grid
- A regular grid of N×N vertices over `extent × extent`, centred on the anchor, with heights from `DemSampler` (bilinear).
- Indices: GL triangles, `ShortArray`, so **the hard GLES2 limit is 65 535 vertices per draw** (16-bit indices; `OES_element_index_uint` may exist on Mali-450 but is not relied on). With 129² = 16 641 vertices one draw is enough. A larger tier is split into row bands of at most 65 k vertices.
- **Vertex layout:** position as 3 floats (12 B), normal as 3 bytes, normalised, plus 1 byte of padding (4 B) = 16 B per vertex. 129² vertices are 266 KB of VBO, and 2 × 128² triangles are 98 304 indices = 197 KB of IBO. Both are `GL_STATIC_DRAW` and rebuilt only on a re-centre.
- **Normals** come from central differences of the height grid. They are only used if lighting is on, which it is by default (see section 6: the dark style needs it).
- **Vertical exaggeration** is 1.0 by default (the coast is steep enough already). A Phase 2 setting allows 1.0–2.0.
- **Skirt:** none in v1. The edge is fogged, and from the camera's height and pitch the far edge is never seen from below.
- **Level of detail** is optional (Phase 2): a fine inner grid plus a coarse outer ring, each a separate draw. A gap between the two is hidden by giving the inner grid's boundary vertices the outer ring's interpolated heights, the usual "stitch to the coarser neighbour".

| Tier | Grid | Extent | Spacing | Vertices | Triangles |
|---|---|---|---|---|---|
| Low | 65² | 4 km | 62 m | 4 225 | 8 192 |
| **Mid (default)** | **129²** | **4 km** | **31 m** | **16 641** | **32 768** |
| High (Phase 2) | 129² inner + 65² ring | 2 km + 8 km | 16 m / 125 m | 20 866 | 40 960 |

The mid tier's 31 m spacing matches the data. With the camera 150–350 m behind the car, a hairpin above Budva shows as a real bend in the ground, not a smoothed hump.

## 5. The map picture (the riskiest part)

The texture is **georeferenced**: each picture carries the exact camera it was rendered with, so its world → UV mapping is known and fixed. A slow picture therefore covers the wrong area at worst. It is **never in the wrong place**. This takes most of the sting out of latency.

### 5.1 Options

**(a) `MapSnapshotter` (offscreen).** *Verified* in 10.3.7:
- `MapSnapshotter(Context, MapSnapshotter.Options)`, `start(SnapshotReadyCallback, ErrorHandler)`, `cancel()`.
- Native `setSize(int,int)`, `setCameraPosition(CameraPosition)`, `setStyleUrl`, `setStyleJson`, `setRegion`.
- `getLayer(String)` and `getSource(String)` (public), `addImage(String, Bitmap, boolean)`.
- `protected void addOverlay(MapSnapshot)`; the class is not final, so the overlay can be overridden.
- `Options(int,int)` with `withStyle`, `withStyleJson`, `withStyleBuilder`, `withCameraPosition`, `withRegion`, `withPixelRatio`, `withLogo`, `withLocalIdeographFontFamily`.
- `MapSnapshot.getBitmap()`, `pixelForLatLng(LatLng)` and `latLngForPixel(PointF)`.

It renders on a native `HeadlessFrontend` with its own pbuffer context, and shares the file source (and so the tile cache) with the 2D map.

| Pros | Cons |
|---|---|
| A public, supported API. It runs beside the tile's `MapView` by design (separate frontend and context). | Every `start()` renders from scratch: tile parse, symbol placement, a full draw, then `glReadPixels` (4 MB at 1024²) and a `Bitmap`, all on the weak CPU. Latency on this unit is *unknown*: guessed at 0.3–1.5 s for 1024², and it is the first thing Phase 0 measures. |
| Exact georef: we set centre, bearing and zoom ourselves, and `pixelForLatLng` cross-checks the result. | We then upload the 4 MB bitmap to our texture (about 10–30 ms on Mali-450, *unverified*). This needs strip uploads (5.6). |
| Style JSON, layer visibility and pixel ratio are all under our control. | A third GL context's resources on the GPU (glyph atlas, tile buffers): tens of MB, *unverified*. |
| | It draws attribution and the logo into the bitmap: override `addOverlay` to a no-op and draw our own attribution (section 12). |

**(b) A hidden second `MapView` (texture mode) + `TextureView.getBitmap()` or `MapboxMap.snapshot()`.**

| Pros | Cons |
|---|---|
| Incremental: moving the camera by 200 m reuses parsed tiles and placed labels, so each frame is cheaper than a fresh snapshot. | A `TextureView` only gets a surface when it is attached to a hardware-accelerated window, so the map must be in the view tree: laid out, sized, and composited by RenderThread every frame even if it is invisible. |
| | `getBitmap()` reads back on the **main** thread, which is a UI hitch every refresh. `MapboxMap.snapshot()` reads back on the render thread and still makes a 4 MB bitmap. |
| | It renders continuously unless capped, and it has gestures, a compass and an attribution view. |

**Rejected:** it has all of (a)'s costs, plus the view-tree costs and main-thread readback.

**(c) Zero-copy: a hidden `MapView` drawing into our own `SurfaceTexture`, consumed as an external OES texture.** What 10.3.7 offers (*verified*):
- `MapView.getRenderView()` is public and returns the `TextureView` in texture mode.
- That `TextureView`'s `SurfaceTextureListener` is MapLibre's package-private `TextureViewRenderThread`.
- `TextureView.getSurfaceTextureListener()` is public (API 14).
- `TextureViewRenderThread.onSurfaceTextureAvailable(SurfaceTexture, int, int)` and `onSurfaceTextureSizeChanged` are public methods.
- It makes its EGL window surface from whatever `SurfaceTexture` it is given.
- `MapView`'s renderer is the anonymous `MapView$4 extends TextureViewMapRenderer`.
- `MapRenderer`'s draw entry points (`onSurfaceCreated`, `onSurfaceChanged`, `onDrawFrame`) are `protected`, `nativeRender` is private, `MapView.mapRenderer` is a private field, and `initialiseDrawingSurface` is private.
- There is no public way to inject a renderer.

The trick, then:
1. Create a `MapView(context, options.textureMode(true))` that is never attached to a window.
2. Call `measure`/`layout` on it by hand to the texture size, so `onSizeChanged` sizes the native map. Whether this works on a detached view is *unverified*.
3. Drive `onStart`/`onResume` ourselves.
4. On our GL thread, create `SurfaceTexture(oesTexId)` and hand it to `(mapView.renderView as TextureView).surfaceTextureListener.onSurfaceTextureAvailable(st, w, h)`.
5. MapLibre's own render thread then draws into our `SurfaceTexture`. We call `updateTexImage()` when we choose to and sample `samplerExternalOES`.

| Pros | Cons |
|---|---|
| No readback, no upload and no `Bitmap`: the picture never touches the CPU. | Relies on undocumented internals. These are frozen, though: we are pinned to 10.3.7 forever (it is the ES 2.0 ceiling), so "may break on upgrade" doesn't apply. |
| Incremental rendering, like (b). Camera moves are cheap. | A detached `MapView` may misbehave (no window means no `onAttachedToWindow`, connectivity receivers and so on). *Unverified.* |
| We decide when to latch a frame (after `OnDidFinishRenderingMapListener` reports fully rendered), so partly loaded tiles are never shown. | `GL_OES_EGL_image_external` on Mali-450 is *unverified* (very likely, since it is the camera and video path). Whether the BufferQueue blocks MapLibre's producer when we don't latch is *unverified*. |
| | An OES texture holds only the latest frame. Crossfading needs a blit into a regular 2D texture (one quad into an FBO, about 1 ms). The `SurfaceTexture` transform matrix must be folded into the georef. |

### 5.2 Recommendation
**Ship (a) `MapSnapshotter` first; keep (c) as the planned upgrade; reject (b).**
- (a) is the only option built entirely from public API. Its cost is bounded by how often we ask: the refresh policy caps it at one picture in flight and at most one every 1.5 s. Phase 0 measures its latency and CPU on the unit.
- If (a) misses the Phase 0 budget (latency p95 > 3 s, or more than about 35 % of a core at the default refresh), (c) is the next spike. Its risks are all things a day on the unit can confirm or kill.
- `MapTextureSource` is the seam: the renderer takes a `MapPicture` that is either a `Bitmap` plus `Georef`, or a GL texture plus `Georef` plus a transform matrix. Nothing else changes between (a) and (c).

### 5.3 Size, resolution and orientation
Metres per pixel at MapLibre zoom `z` with 512 px tiles and pixel ratio 1 is `40 075 016.686 × cos(lat) / (512 × 2^z)`. At Budva that is 1.77 m at z15 and 7.1 m at z13.

**Two pictures, near and far** (the far one from Phase 2; the MVP has only the near one, with fog closing in before its edge):

| Picture | Size | Ground | Metres/px | MapLibre zoom (ratio 2) | Car's place |
|---|---|---|---|---|---|
| Near | 1024² | 1.25 km | 1.22 m | ≈ 14.6 | Centred across, 25 % up from the bottom (it covers about 940 m ahead and 310 m behind) |
| Far | 1024² | 6 km | 5.9 m | ≈ 12.3 | 30 % up from the bottom |

- **Pixel ratio 2** (`withPixelRatio(2f)`, with `Options` at 512×512 logical giving a 1024² bitmap; the logical-vs-physical size is *unverified*, so check it against `getBitmap().width`). It doubles line widths and text size relative to the ground. That is what keeps roads and labels readable after the perspective squashes them about 3–5× near the horizon. The zoom in the table is chosen for ratio 2.
- **Texture size:** `GL_MAX_TEXTURE_SIZE` on Mali-450 is believed to be 4096 (*unverified*; query it). We use POT sizes only (1024, or 2048 at the high tier), because Mali-4xx NPOT support is limited (no mipmaps, clamp only), so POT is the safe side. Memory per 1024² picture: 4 MB as RGBA8888, or **2 MB as RGB565** (the map is flat colours, so 565 banding is acceptable; to be checked on the dark style), plus a third for mipmaps. Double-buffered near and far in 565 with mips come to about 11 MB of GPU memory. A 2048² near picture in RGBA with mips is 21 MB per buffer: this is why 2048 is reserved for the high tier.
- **Blur near the car vs shimmer far away:** the ground under a tilted camera is seen at a grazing angle. Mipmapping (`GL_LINEAR_MIPMAP_NEAREST`, mips from `glGenerateMipmap` after upload) blurs the far ground. Without mips, labels and thin roads shimmer as the car moves. Anisotropic filtering is not expected on Mali-4xx (*unverified*). The near/far split is the real fix: the near picture's 1.2 m pixels stay sharp where the eye looks, and the far picture's 6 m pixels are already right for the distance. Near uses `GL_LINEAR` without mips (it is never far enough to shimmer badly). Far uses mips.
- **North-up vs heading-up.** North-up covers a disc around the car, which takes about 4× the pixels for the same range ahead, and labels read upside down when driving south. Heading-up spends its pixels ahead, where the frustum is, and keeps labels upright, but it needs a new picture whenever the heading changes, and serpentines change heading all the time. **Chosen: heading-up quantised to 30° steps with 10° of hysteresis.** Labels are then at most about 20° off upright, and a hairpin costs one or two refreshes, not a stream of them. The far picture uses 45° steps.
- **Labels:** they are rendered as the 2D map draws them. Point labels may sit tilted (by up to the quantum) and will look squashed far ahead. The texture style raises text size by about 1.3× (section 5.4) and drops the smallest POI labels, which can't be read on a tilted ground anyway. Billboard labels drawn by us are a possible later item, not v1.

### 5.4 Style
- The same style URL as the 2D tile: `MapServers.current.value.styleDark` or `.styleLight`, picked by `DashColors.Light`, as in `MapLibrePanel`.
- **The dark contrast pass applies.** `MapDarkContrast.apply(style: Style)` takes a `Style`, and the snapshotter has none. There are two ways to get the same result:
  - **(preferred)** Fetch the style JSON once through `Http.client`, apply a new `MapDarkContrast.applyToJson(json)` built on the same pure `adjustmentFor(id)` table, and pass the result to `Options.withStyleJson`. This is pure and unit-testable alongside `MapDarkContrastTest`.
  - Alternatively, after the style loads (the `Observer` callback), call `snapshotter.getLayer(id)?.setProperties(...)`. Whether a layer from the snapshotter accepts property changes is *unverified*.
- **Texture-only tweaks,** applied in the same JSON pass:
  - text size × 1.3;
  - POI layers hidden below class "major";
  - the dark style's `background` lifted (for example `#14161A` → `#262A31`). Without this, lit slopes of a near-black texture still read as black, and the relief disappears; Phase 0 judges this.
  - building fills kept flat.
- **Route line: in the picture for the MVP.** The style JSON gets the current route as a GeoJSON source plus line layers in the `RouteLine` colours (`RouteLine.lineColor` and `casingColor`), from `InAppNav.guidance.value?.route`. A route change rebuilds the JSON and the snapshotter, which is rare. A 3D ribbon drawn by us is Phase 2 (section 6).
- **Puck: always ours, never in the picture.** A puck in the picture would be frozen where the car was when it was rendered. The snapshotter has no location component, so nothing needs removing.

### 5.5 Refresh policy (`TextureRefreshPolicy`, pure)
Inputs: the drawn car pose, speed, the georef of the current picture, the last request time, whether a picture is in flight, and the style/route/day-night generation. Output: `None`, `Near(spec)` or `Far(spec)`.

| Trigger | Near | Far |
|---|---|---|
| Car past this share of the picture's forward reach | 40 % (about 375 m) | 40 % (about 1.6 km) |
| Car within this of any edge | 250 m | 1 km |
| Quantised heading changed | 30° step, 10° hysteresis | 45° step, 15° hysteresis |
| Style, route or day/night changed | at once | at once |
| Minimum interval | 1.5 s | 5 s |
| In flight | at most 1 picture in total; near wins | |
| Car still (`SmoothFollow.STILL_MPS`) | no refresh, except for the generation change | same |

The new picture is centred on the position the car **will** have when it arrives: the current position plus speed × the measured p50 latency, along the course, clamped to 300 m. At 90 km/h (25 m/s) the near picture refreshes about every 15 s on a straight road. On the serpentine above Budva at 30–40 km/h it refreshes every 3–6 s, mostly on heading.

### 5.6 Double buffering and swaps
- Each picture (near, far) has a **front** and a **back** texture. A new `Bitmap` is converted to RGB565 on `Default` (the 2 MB that results is the upload).
- The GL thread uploads it into the back texture **in strips**: `glTexSubImage2D` of 128 rows per frame, so 8 frames for 1024². No frame then carries more than about 2–4 ms of upload (*unverified*; Phase 0 times one full upload and one strip). The mips are built after the last strip.
- Then the back and front swap, with a **400 ms crossfade**: for its duration the shader samples both, each with its own georef matrix. Both are georeferenced, so the ground lines up and only labels and edges blend. The bitmap is recycled after upload.
- Option (c) has the same shape: latch, then blit the OES texture into the back texture, then crossfade.

## 6. Rendering

### Frame
1. `glClear` colour (sky) + depth.
2. Sky: one full-screen quad with a vertical gradient (horizon colour → zenith colour), depth test off. The light and dark colours follow `DashColors.Light`.
3. Terrain: depth test on, back-face culling on, one draw per mesh band (1 in the default tier).
4. Route ribbon (Phase 2): depth test on, depth write off, lifted 1.5 m, `glPolygonOffset` as a backstop.
5. Puck: **depth test off**, drawn last, so a DEM error, a ridge or a bridge never hides the car.
6. Compose overlays over the `TextureView`: the "2D" button, the status chip and the attribution text. These are not GL.

There are 3–5 draw calls per frame and one program switch for each of sky, terrain, ribbon and puck. This is trivial for the GPU's command processing.

### Shaders (GLSL ES 1.00)
**Mali-4xx fragment shaders have no `highp`**, only fp16 `mediump`. Anything precise is therefore computed per vertex (fp32), and UVs are passed as varyings and used **unmodified** in `texture2D`. The usual Mali-400 advice is that a straight varying read samples at full precision; this is *unverified* on this unit. In Phase 0, look for swimming texels near the car.

```glsl
// terrain.vert
attribute vec3 aPos;            // local metres: x east, y north, z up (already exaggerated)
attribute vec3 aNormal;         // normalised bytes
uniform mat4 uMvp;
uniform mat3 uNearUv;           // world xy → near picture uv (Georef)
uniform mat3 uFarUv;            // world xy → far picture uv
uniform vec3 uEye;              // camera, local metres
uniform vec3 uSun;              // normalised direction to the light
uniform vec2 uFog;              // start, end (metres)
varying vec2 vNearUv;
varying vec2 vFarUv;
varying float vFarW;            // 0 = near picture, 1 = far picture
varying float vFog;
varying float vLight;
void main() {
  vec3 uvn = uNearUv * vec3(aPos.xy, 1.0);
  vNearUv = uvn.xy;
  vFarUv = (uFarUv * vec3(aPos.xy, 1.0)).xy;
  vec2 edge = min(vNearUv, 1.0 - vNearUv);          // distance to the near picture's edge in uv
  vFarW = 1.0 - smoothstep(0.0, 0.08, min(edge.x, edge.y));
  vFog = smoothstep(uFog.x, uFog.y, distance(aPos, uEye));
  vLight = 0.55 + 0.45 * max(dot(aNormal, uSun), 0.0);
  gl_Position = uMvp * vec4(aPos, 1.0);
}
```

```glsl
// terrain.frag
precision mediump float;
uniform sampler2D uNear;
uniform sampler2D uFar;
uniform vec3 uSky;
varying vec2 vNearUv; varying vec2 vFarUv;
varying float vFarW; varying float vFog; varying float vLight;
void main() {
  vec3 c = mix(texture2D(uNear, vNearUv).rgb, texture2D(uFar, vFarUv).rgb, vFarW);
  gl_FragColor = vec4(mix(c * vLight, uSky, vFog), 1.0);
}
```

- The MVP has no far picture: a variant shader with one sampler, where `vFarW` falls back to a flat land colour.
- The crossfade is a third variant (old and new near pictures, plus a `uFade` uniform), used only for 400 ms.
- Variants are separate programs, not `if` branches, because branching is expensive on Mali-4xx.
- **Lighting:** a per-vertex Lambert term from a fixed sun (azimuth 315°, elevation 45°, the classic hillshade light), in the 0.55–1.0 range. It is on by default: it is what makes relief read at all on a dark map, and costs nothing per fragment.
- **Fog** hides the mesh edge and the end of the picture: it starts at 55 % of the reach and is full at 95 %. The reach is `extent / 2` in the MVP and the far ring's edge with LOD. The fog colour is the sky's horizon colour, so the ground melts into the horizon.
- **Depth:** request a 24-bit depth config and fall back to 16. With near = 10 m and far = 6 km, a 16-bit buffer resolves about 24 m at 4 km, which is acceptable under fog, and 24-bit resolves 0.1 m. Whether a 24-bit config is offered is *unverified*.
- **Antialiasing:** none (no MSAA config is requested). It costs too much on this GPU, and fog plus mips soften the far edge enough.

### Puck
- A flat chevron (an arrow made of 2 triangles, about 14 m long, in `DashColors.Accent`), lying on the ground and turned to the drawn bearing, with a soft disc under it.
- Its position comes from `FollowTrack.at(now)` on the GL thread. Its height is `DemSampler` at that point + 1 m.
- **The puck's height is filtered.** It follows the DEM with a vertical speed of at most |speed| × 0.15 (a 15 % grade) + 0.5 m/s. A bridge over a gorge or a tunnel portal then doesn't make the puck dive or jump: it glides at a plausible grade. Inside a long tunnel (Sozina, 4.2 km) there is no GPS anyway, so the puck holds.
- GPS altitude is **not** used: on API 27 it is the ellipsoid height (see the `ElevationRecorder` note) and it is noisy.

### Route ribbon (Phase 2, optional)
- Built from `InAppNav.guidance.value?.route` geometry, densified to a point every 20 m, and draped (each point at DEM height + 1.5 m).
- Extruded into a 10 m wide triangle strip, with only the part inside the mesh area kept: at most about 2 000 vertices. It is rebuilt on a route change or a re-centre.
- It is drawn in the route colour with fog applied. With it, the route can leave the picture's style JSON, and the picture no longer needs a refresh when the route changes.

### Frame rate and pausing
- **30 fps cap**, the same constant as `TILE_FPS` and `WALLPAPER_FPS` (it moves to a shared constant). The loop is driven by `Choreographer` and skips every other vsync at 60 Hz.
- **Render on demand:** when the car is still (`StationaryDetector`, the same signal `MapLibrePanel` uses), no fix is gliding, and no crossfade or upload is running, no frame is drawn at all. A still car costs 0 fps, which is better than the 2D map's `HIDDEN_FPS`.
- **Hidden:**
  - With `LocalPageActive` false, or the lifecycle paused, the loop stops and the texture source is cancelled.
  - With the view gone, all GL objects are freed and the snapshotter is `cancel()`ed and dropped.
  - EGL context loss (`EGL_CONTEXT_LOST`) rebuilds everything from the immutable inputs.

## 7. Camera (`ChaseCamera`, pure)

| Parameter | Value | Notes |
|---|---|---|
| Distance behind the car | 160 m at 0 km/h → 320 m at 90 km/h | Linear in speed, smoothed with τ = 2 s |
| Height above the car | 0.42 × distance | Gives a pitch of about 23° to the car |
| Look-at point | 120 m ahead along the drawn bearing, at the DEM height there | The car then sits in the lower third |
| Vertical field of view | 45° | About 73° horizontal at 16:9 |
| Near / far plane | 10 m / the fog end + 200 m | |
| Yaw smoothing | Critically damped spring on the bearing, τ = 0.8 s, wrapping through 0/360 the way `SmoothFollow.lerpBearing` does | A hairpin swings the view smoothly, not in a whip |
| Terrain clearance | eye z ≥ max(DEM(eye) + 40 m, max DEM along eye→car at 5 samples + 20 m) | Rises with τ = 0.3 s and sinks with τ = 2 s, so a ridge behind the car lifts the camera promptly and it settles slowly |
| Stopped | Holds yaw and distance (the same rule as `FollowTrack`: speed < `STILL_MPS` holds the heading) | |

**Inputs:** `FollowTrack.at(now)` on the GL thread, which gives the drawn position and bearing, one fix interval behind, gliding, as the 2D puck does. Using the same class guarantees the 3D puck moves exactly like the 2D one. The speed is `Location.speed` from the last fix.

**Output:** an eye and a target in `LocalFrame` metres, giving the view matrix (`android.opengl.Matrix.setLookAtM`, with z up), and the projection.

**Gestures:** none in the MVP, where a tap leaves. Phase 2 may add a pinch to scale the distance (0.6–1.6×), saved like `userZooms`.

## 8. Performance budget (Mali-450, estimates)

All of this is estimated; Phase 0 measures it. The core count of the GPU is not confirmed (Mali-450 MP2 or MP4, at about 500–700 MHz).

| Cost | Estimate | Basis |
|---|---|---|
| Fill: a large map tile (about 850×720 = 0.6 Mpx) | sky + terrain ≈ 1.3× overdraw × 30 fps ≈ 24 Mpx/s | Mali-450 MP2 has about 1 Gpx/s theoretical, so this is a few %. Full screen is about 36 Mpx/s. |
| Texture samples | 1 per fragment (MVP), 2 with near and far, 3 during a crossfade: ≤ 75 M/s | Mali-450 does about 1 bilinear sample per clock per core; fine. |
| Vertices | 16.6 k × 30 = 0.5 M/s | Mali-450's single vertex processor is good for tens of M/s; fine. |
| Draw calls | 3–5 per frame | Trivial. |
| Texture upload | 2 MB (RGB565 1024²) per refresh in 8 strips of 256 KB | Each strip should be ≤ 3 ms (*unverified*). |
| VBO upload | 0.46 MB per re-centre (≈ every 1 km) | Once a minute at speed. |
| DEM decode | ≈ 5–10 ms per 256² PNG plus 1 ms Terrarium, per new tile | A tile every few km: noise. |
| Mesh build | ≈ 5–15 ms per 129² grid with normals (Kotlin float loops, about 1 M operations) | `Default` thread, once per km. |
| **Snapshot (a)** | **Unknown: the dominant cost.** Guess: 0.3–1.5 s on a MapLibre native thread at near 100 % of a core during it, plus the readback, the `Bitmap`, and the RGB565 conversion (≈ 10 ms). | One every 3–15 s while driving puts it at roughly 10–40 % of one core on average. |
| HWUI composite of the `TextureView` | The same as the 2D map tile's today | 30 fps → it dirties the dashboard window 30× per second. |

**Running MapLibre offscreen and our view at once.** Up to three GL contexts are live: the 2D tile's `MapView` at 10 fps, the snapshotter's pbuffer context in bursts, and ours at 30. The GPU time-slices between them, and Mali-4xx context switches flush the tile buffers.
- The risks are a frame miss in our view whenever a snapshot renders, and GPU memory (snapshotter glyph and tile buffers plus our 11 MB).
- **Mitigations:**
  - only one snapshot in flight;
  - snapshots never while the car is still (nothing to refresh);
  - the 2D map stays at `HIDDEN_FPS`, as now;
  - the snapshotter is dropped when the 3D view hides;
  - if Phase 0 shows our frames stalling during snapshots, the fallback is to drop to 20 fps for the snapshot's duration rather than miss frames at random.

**Quality tiers and the automatic fallback (`TerrainQuality`, pure):**

| Tier | Mesh | Near picture | Far picture | Refresh min | fps |
|---|---|---|---|---|---|
| High | 129² + 65² ring | 2048² RGB565 | 1024² | 1.5 s | 30 |
| **Mid (default)** | 129² | 1024² | 1024² (Phase 2) | 1.5 s | 30 |
| Low | 65² | 1024² | none (fog closer) | 3 s | 30 |
| Minimal | 65² | 512² | none | 5 s | 20 |

The watchdog keeps a rolling 5 s window of frame times (from eglSwapBuffers to eglSwapBuffers on the GL thread). If the p90 is over 40 ms for two windows in a row, it steps down one tier. It steps up after 60 s under 25 ms, at most once a drive. The tier is saved in `map_tile` prefs, so the next start begins at the last working tier. The current tier shows in the debug overlay only.

## 9. Testing

### JVM unit tests (`app/src/test/java/com/openauto/dash/terrain/`)
- **`TerrariumTest`:**
  - `(128,0,0)` → 0 m;
  - `(131,74,0)` → 842 m;
  - `(0,0,0)` → −32 768 m;
  - B/256 fractions;
  - a fixture PNG decoded through `javax.imageio` with known spot heights (the Lovćen summit area should be about 1700 m, and sea pixels about 0 m).
- **`TileMathTest`:**
  - lat/lon → z12 tile and back;
  - tile bounds;
  - metres per pixel at 42.29° (28.3 m at z12);
  - antimeridian and pole clamps;
  - Mercator ⇄ local round trip within 1 cm at 5 km.
- **`DemSamplerTest`:**
  - bilinear in a tile;
  - continuity across a tile edge and a tile corner (four tiles);
  - missing tile → nearest known;
  - nothing known → 0.
- **`TerrainMeshTest`:**
  - vertex and index counts per tier;
  - every index < 65 536 and < the vertex count;
  - counter-clockwise winding seen from +z;
  - a flat DEM gives normals of (0,0,1);
  - a 45° plane gives the right normal;
  - re-centring to a snapped anchor leaves shared vertices bit-identical in world position.
- **`GeorefTest`:** world → UV for a picture at bearing 0/90/210 and pixel ratio 2. The car lands at (0.5, 0.75), and the corners land on the right lat/lon. An Android instrumentation test cross-checks the same numbers against `MapSnapshot.pixelForLatLng` on the unit in Phase 0.
- **`TextureRefreshPolicyTest`:**
  - each trigger in the table;
  - hysteresis (oscillating ±12° around a step boundary makes no requests);
  - minimum interval;
  - one in flight;
  - stopped → none;
  - a generation change → at once.
- **`ChaseCameraTest`:**
  - eye never below DEM + clearance over a synthetic ridge;
  - yaw wraps 350° → 10° the short way;
  - distance follows speed;
  - convergence within 3τ;
  - stopped holds.
- **`TerrainQualityTest`:** step-down and step-up rules, and the once-per-drive limit.
- **`MapDarkContrastTest`** gains: `applyToJson` changes exactly the layers `adjustmentFor` names, leaves unknown ids alone, and applies the 3D-only tweaks.
- The existing `SmoothFollowTest` covers the glide. The GL thread just uses `FollowTrack`.

### Fixed-area offline fixture
- `app/src/test/resources/terrain/terrarium-z12/` holds the 3×3 z12 tiles around Budva–Bečići–Lovćen, about 0.5–1 MB, downloaded once and committed with an attribution note.
- The same files go into `app/src/debug/assets/terrain/`. Debug builds with `adb shell setprop debug.dash.terrain.fixture 1` read the DEM from assets and never touch the network. This is the same pattern as `debug.dash.emumap`.
- Paired with a recorded GPS track: the Bečići → Budva old road → the serpentine towards Cetinje, replayed through `DemoMode` (`LocationFeed.demoWrite`) or as an asset-backed track. Every run on the unit then sees the same drive.

### Emulator
The `radio-emu` AVD (API 27 x86, 1280×720 @160, GLES 2.0) is useful for **function, not speed**. With `adb shell setprop debug.dash.emumap 1` on a debug build and `-gpu host`, the real MapLibre map runs (`isEmulator` is then false), and so do the snapshotter and our renderer. Check there:
- that entering and leaving work;
- that pictures line up with the ground (roads lie in their valleys);
- that crossfades don't flash;
- that a re-centre is invisible;
- the offline and no-GPS states;
- that rotation and resizing (tile size changes in edit mode) survive.

The host GPU says nothing about Mali-450 timing.

### On the unit (Phase 0 and every phase's acceptance)
- **Our frame times:** HWUI's `dumpsys gfxinfo com.android.launcher3 framestats` only sees the RenderThread compositing our `TextureView`, not our GL thread. The GL thread therefore logs its own: tag `DashTerrain`, once every 5 s, with p50/p90/max frame time, uploads, snapshot latency and tier.
- **System traces:** `atrace` / `systrace` with `gfx view sched freq` around a 60 s replay, to see the snapshot bursts against our frames and RenderThread.
- **CPU per thread:** `top -H -p <pid>` as in the 2026-10-22 perf review. Compare 2D map only, 3D parked, and 3D driving (replay).
- **Memory:** `dumpsys meminfo com.android.launcher3` (PSS, and the GL/`mtrack` lines if the Mali driver reports them), before, during and after 15 minutes of the replay. Look for growth, which would mean a leak of bitmaps or textures.
- **GL limits:** `GL_MAX_TEXTURE_SIZE`, the extension list (`OES_EGL_image_external`, `OES_element_index_uint`, `OES_packed_depth_stencil`, `EXT_texture_filter_anisotropic`) and the depth sizes of the chosen EGL config. Log them once at start-up.
- All adb commands on the unit stay within the usual 20 s timeout. Long captures run with an `atrace --async_start` / `--async_stop` pair.

## 10. Phases

### Phase 0: spike on the unit (3–4 days)
A debug-only screen (behind the `debug.dash.terrain.fixture` flag), not wired into the tile options. It shows:
- the fixture DEM (z12, 3×3) as the 129² mesh with lighting and fog;
- **one** near picture from `MapSnapshotter` (dark style + contrast JSON, ratio 2, 1024²), taken at the start position;
- the chase camera on the replayed track, with the puck;
- the snapshot refreshed on a fixed 3 s timer, to measure it repeatedly.

It records:
- frame-time p50/p90;
- snapshot latency p50/p95;
- upload time (full and strip);
- process CPU (`top -H`) and PSS;
- the GL limits listed above;
- whether `pixelForLatLng` agrees with `Georef` (within 2 px);
- WebP decode of one Mapterhorn tile;
- how the dark style reads lit (screenshots for the user).

**Go if all of these hold** at a large-tile size (about 850×720), with the 2D map composed at `HIDDEN_FPS` beside it:
- frame time p50 ≤ 33 ms and p90 ≤ 40 ms, with no snapshot running;
- p90 ≤ 50 ms during snapshots;
- snapshot latency p50 ≤ 1.5 s and p95 ≤ 3 s;
- a strip upload ≤ 4 ms, and no visible hitch at a swap;
- PSS increase ≤ 80 MB over the 2D map alone, flat over 15 minutes;
- average CPU of the snapshot loop at 3 s ≤ 35 % of one core;
- the relief clearly visible on the dark style, as judged by the user from the screenshots.

**No-go** on the snapshot numbers alone → spike (c) for 2 days (section 5.1), then decide. No-go on our own frame times → try the low and minimal tiers. If minimal still misses, see the fallbacks in section 11.

### Phase 1: live-following MVP (8–12 days)
- `DemTileCache` (network + disk + memory), `DemSampler`, re-centring meshes, and the `LocalFrame` swap.
- `SnapshotTextureSource` with the refresh policy (near picture only, heading-up quantised), RGB565 strip uploads, and the crossfade.
- `ChaseCamera` with clearance, the puck with height filtering, sky and fog, and render on demand.
- The tile integration: the `MapTileOptions` switch (default off), the 2D ⇄ 3D button, `HIDDEN_FPS` for the 2D map, and pause when hidden.
- Status chips (waiting for GPS, terrain loading, offline) and the attribution overlay.
- The route in the picture (style JSON).
- The quality watchdog with the mid, low and minimal tiers.
- All JVM tests in section 9.

**Accept** when:
- a 30-minute drive Bečići → Budva → Cetinje serpentine → back runs at p50 ≤ 33 ms;
- there are no crashes or black views across ACC off/on, page swipes, entering and leaving 3D 20 times, and day/night switching;
- the map picture is never visibly misaligned with the valleys;
- data use is ≤ 10 MB for the drive with a cold cache, and nothing is fetched on the second run;
- with the network off on a cached route the view works; on an uncached one it degrades with the chip and doesn't stall;
- 1448+ existing tests and the new ones pass.

### Phase 2: polish (8–10 days)
- The far picture and the near/far blend, mipmapped far.
- The route as a 3D ribbon (the picture then drops the route layers).
- The high tier (2048² near, LOD ring).
- Settings in the tile panel: exaggeration (1.0 / 1.5 / 2.0), camera distance (pinch or 3 steps).
- An "offline terrain for this area" prefetch (a 50 km radius at z12 ≈ 150 tiles, ≈ 10–20 MB), with its size shown before download.
- The Mapterhorn source as an option in the map servers settings.
- Full-screen mode, if Phase 1's numbers at 1280×720 allow it.

**Accept** when the Phase 1 criteria hold at the new default, plus:
- the far blend shows no visible seam;
- the ribbon never sinks under the ground on the fixture route;
- the full-screen variant (if shipped) runs at p50 ≤ 33 ms at 1280×720.

Total: about **19–26 working days**, of which Phase 0 is the decision point.

## 11. Risks, open questions and fallbacks

| # | Risk | Likelihood | Effect | Handling |
|---|---|---|---|---|
| 1 | Snapshot latency or CPU too high on this CPU | Medium-high | Stale or cropped ground ahead, a hot unit | Measured in Phase 0. Then (c) zero-copy. Then lower size and rate (minimal tier). |
| 2 | Three GL contexts on Mali-450: our frames stall during snapshots | Medium | Stutter every few seconds | One in flight; 20 fps during a snapshot; snapshots only while moving; (c) removes the third context's draw-from-scratch. |
| 3 | The dark style draped and lit reads as black hills | Medium | No visible relief: the feature's whole point | Lifted texture background, the lighting term, and the light style as an option. Judged from Phase 0 screenshots. |
| 4 | Labels unreadable under perspective | High (far) / low (near) | Clutter far ahead | Pixel ratio 2, text × 1.3, small POIs hidden, fog. Billboard labels later if wanted. |
| 5 | GPU memory pressure (2D map + snapshotter + ours) → a context loss or low-memory kill | Low-medium | The view rebuilds, or the launcher restarts | RGB565, at most 11 MB of our textures at mid, the snapshotter dropped when hidden, `onTrimMemory` drops to minimal. |
| 6 | Undocumented internals for (c) misbehave on a detached `MapView` | Medium | (c) not possible | (a) stays. (c) is only an upgrade path. |
| 7 | DEM artefacts (canopy, bridges, tunnels, coast cliffs) | Certain, minor | The puck or ground looks odd | Puck depth test off, a height filter at a 15 % grade, camera clearance. |
| 8 | AWS bucket availability or terms change | Low | No new terrain | Disk cache, the `MapServers.terrain` override, and Mapterhorn as an alternative. |
| 9 | Mali-4xx precision (fp16 fragment): texture swimming near the car | Low-medium | Shimmer | UVs per vertex and unmodified varyings. If it still swims, split the near picture's georef origin to the car. |
| 10 | The 30 fps TextureView keeps the dashboard window busy (perf review item 2) | Certain | CPU on RenderThread, as with the moving 2D map | No worse than the 2D map today, with the 2D map at 10 fps meanwhile and 0 fps while stopped. |

**Open questions** (answered by Phase 0 unless noted):
- The Mali-450 core count and clock on this SoC, `GL_MAX_TEXTURE_SIZE`, the available depth sizes, OES external support, and anisotropic filtering.
- Whether `MapSnapshotter` can be reused for many `setCameraPosition` + `start` cycles without leaking, or should be recreated every N pictures.
- Whether `Options(w, h)` is logical size × ratio or physical.
- Whether property changes on `snapshotter.getLayer(id)` take effect. They are not needed if the JSON route works.
- Whether the user wants the light style by day in 3D (the 2D tile follows `DashColors.Light`). *For the user.*
- Whether the default 4 km mesh, at a 160–320 m chase distance, looks right on the coast, or needs a higher, steeper camera. *Judged on the unit.*

**If the spike fails**, in order of preference:
1. **(c) zero-copy pictures** instead of snapshots, if the failure is in the snapshot cost.
2. **A lower tier only** (65² mesh, 512² picture, 20 fps, refresh ≥ 5 s): coarse but real 3D.
3. **Parked/slow-only 3D:** the 3D view refreshes its picture only under 30 km/h or when stopped, and otherwise keeps the last one plus lighting on plain land colour.
4. **Relief without the map:** lit, fogged terrain in land and sea colours (sea where DEM ≤ 0), with our puck and the route ribbon on top, and no MapLibre picture at all. This removes the riskiest part entirely and keeps "real 3D relief". It is the floor of this feature.
5. Out of this feature's scope, noted only: `RasterDemSource` + `HillshadeLayer` on the 2D map (supported by 10.3.7, *verified* classes, and `terrarium` appears in the `.so`). This is shading, not 3D, so the user has ruled it out as the feature.

Raster XYZ tiles stitched into a picture (no MapLibre offscreen at all) would be cheap, but no free dark raster source allows app use (the OSM tile servers' usage policy forbids it), so it is not on the list.

## 12. Licensing and attribution
- **AWS Terrain Tiles (Mapzen / Tilezen "joerd")**:
  - Free to use. The tiles are built from several sources, which require attribution in the product: SRTM (NASA / USGS), GMTED2010 (USGS), ETOPO1 (NOAA), and for Europe **EU-DEM**, whose required line is "Produced using Copernicus data and information funded by the European Union - EU-DEM layers". Further sources apply in other countries.
  - Copy the exact text from the tileset's attribution document (`tilezen/joerd`, `docs/attribution.md`) when implementing. Do not paraphrase. The exact wording is *unverified* here.
  - Short form on screen: "Terrain: Mapzen, Copernicus EU-DEM, NASA SRTM"; the full text goes in the app's About/licences screen.
- **Mapterhorn** (optional): attribution required, as "© Mapterhorn" plus its source list. The terms are *unverified*: check mapterhorn.com before shipping it as an option.
- **OpenStreetMap data** (via OpenFreeMap / OpenMapTiles): ODbL. "© OpenStreetMap contributors" must be visible on the map view, together with "© OpenMapTiles" and "OpenFreeMap". The draped picture counts as a map view. We suppress the snapshotter's own overlay (`addOverlay` overridden), because perspective would squash it unreadably, so the 3D view must show the attribution itself: a small Compose text in a corner, over the GL view, always visible, the same as the 2D tile's attribution.
- **Using the AWS bucket and OpenFreeMap:** the expected traffic (a few MB per drive per unit) is well within both. The `MapServers` override lets heavy users point at their own mirrors, as the style and routing URLs already do.
