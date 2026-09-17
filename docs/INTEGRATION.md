# Integration

How to draw a glTF asset with GemRender from another mod: registering the asset, rendering it on a
block entity, and the one thing the API cannot do.

This is the consumer-facing guide, and it is self-contained: everything you need to draw a model is
here. Where a rule below looks arbitrary, the reasoning is in the maintainers' notes
(`gemrender-internal/docs/`), which are kept outside this repository.

GemRender ships for three Minecraft versions, built from one source tree:

| Minecraft | Loader | Java |
|---|---|---|
| 1.21.1 | NeoForge 21.1.248 | 21 |
| 1.20.1 | MinecraftForge 47.4.23 | 17 |
| 26.1 | NeoForge 26.1.2.109 | 25 |

Flywheel rides inside GemRender's jar -- 1.0.6 before 26.1, a port of it on 26.1 -- so there is
nothing else to install. On 26.1 that also means a Flywheel of your own in `mods/` is a duplicate mod
id and a hard load failure. Everything here is client side.

**The API is the same on all three**, and that is a checked claim rather than an intention: the public
signatures of every type below are identical across the three built jars except at two seams, both of
them vanilla's doing -- how an item claims a renderer and how armour does. Those are in
[section 6](#6-items-armour-and-the-hand), beside the code that differs, and
[Version differences](#version-differences) is the complete list.

---

## The one idea

The normal way to animate a model is to produce a deformed mesh per copy per frame, which is exactly
what destroys instancing. GemRender instead uploads a **bone palette** once per frame and gives each
instance an *offset into it*. The vertex shader does the skinning.

Two consequences shape the whole API:

- **A whole asset is one Flywheel `Model`.** However many primitives, materials and nodes it has, a
  hundred copies share one instancer and each copy is a single instance. Parts move relative to each
  other through the palette, not through separate draws.
- **The vertex format is full.** Flywheel has one fixed mesh format with no spare attribute, so
  skinning data is smuggled through `color`, `light` and `overlay`. That is invisible while you use
  the API as intended and lethal the moment you read a mesh yourself. See
  [the vertex format trap](#the-vertex-format-trap).

Sections 1 to 3 are that shape: register an asset, then draw it on a block entity or on an entity.
There is a second path for the shape this one is bad at – a handful of vehicles whose parts each answer
to something different, rather than a crowd sharing a clock;
[section 4](#4-vehicles-and-animation-driven-by-something-other-than-time) is the vehicle.

Both assume the asset arrives with a skeleton in it. [Section 5](#5-rigging-a-model-that-has-none) is
for when it does not: loose part meshes and an animation written in code, joined into a rig here.

---

## 1. Register a model

There is no registry to get into and no builder to call. Declaring a handle in a static field *is*
the registration.

### Asset location

Assets are read straight from the resource manager with no implicit prefix, so the
`ResourceLocation` is the full path under `assets/`. Both `.gltf` and `.glb` work.

```
src/main/resources/assets/mymod/models/drill/drill.glb
                          ^ namespace     ^ becomes mymod:models/drill/drill.glb
```

### Declaring the handle

`GemRenderModels.handle(id)` is cheap, idempotent and lazy. It does not load anything. What it does
is mark the asset as *wanted*, which is what gets it re-imported after every resource reload. Hold it
in a `static final` so that a reload brings your model back without waiting for something to ask for
it again.

```java
public final class MyModels {
    public static final ResourceLocation DRILL =
            ResourceLocation.fromNamespaceAndPath("mymod", "models/drill/drill.glb");

    // Declaring the handle is the registration. Held in a static final so a resource
    // reload re-imports it, rather than waiting for something to ask for it again.
    public static final ModelCache.Handle<GemRenderGltfModel> DRILL_MODEL =
            GemRenderModels.handle(DRILL);

    private MyModels() {
    }
}
```

Read the model with `handle.get()`. `GemRenderModels.get(id)` is the same thing keyed by id.

**`get()` never blocks, and returns `null` until the model is there.** The first call starts the
import on a loader thread and answers `null`; a later frame answers the model. Importing is not
cheap — a model with a stitched atlas takes a few hundred milliseconds the first time it is ever
seen — and the alternative is a render thread stopped for that long at whatever moment a player first
looks at your block.

So handle `null` by drawing nothing and asking again next frame. Do not cache the answer, do not
treat it as a failure, and do not block on it:

```java
GemRenderGltfModel model = MyModels.DRILL_MODEL.get();
if (model == null) {
    return;          // not loaded yet, or broken. Either way, not this frame.
}
```

Calling `get()` every frame is the intended usage: the import is started once, further calls join it
rather than starting another, and a failure is remembered so a broken file is not re-parsed each
frame. `handle.isLoading()` distinguishes "still importing" from "broken" if you want to say so in a
tooltip; `handle.getBlocking()` waits, and is for a command or a test, never for a frame.

### Bedrock geometry and its texture

A `.geo.json` names no texture — the format has none — so the importer looks for a `.png` beside it,
or a `gemrender:texture` in the geometry's description. When neither is right, because the same hull
is worn with several skins and the texture is decided elsewhere, hand it in:

```java
GemRenderModels.built(
        ResourceLocation.fromNamespaceAndPath("mymod", "hull/" + skin.getPath()),
        id -> BedrockImporter.load(GEOMETRY, skin));
```

The texture is baked into the material at import, so **one geometry with two textures is two models**.
That is what the id is for: build it from both, or the second skin quietly gets the first one's model.

When the skins are known up front, declare them together instead and get one model back – see below.

### Variants: one model, several skins

A mob's colour, a vehicle's livery, a team's paint, a gun's camo: same geometry, same rig, same clips,
different pixels. Declared together, they are stitched into one sheet at import and **all of them draw
in one batch** – what separates two copies is two floats on the instance.

```java
private static final ResourceLocation SKINS =
        ResourceLocation.fromNamespaceAndPath("mymod", "variants/drone");

public static final ModelCache.Handle<GemRenderGltfModel> DRONE = GemRenderModels.variants(
        SKINS, ResourceLocation.fromNamespaceAndPath("mymod", "models/drone.gltf"),
        List.of(Map.of(),                          // 0: as the file describes it
                Map.of(HULL, HULL_DESERT),         // 1
                Map.of(HULL, HULL_WINTER)));       // 2
```

A variant is a **texture substitution**, keyed on the location the asset itself names, so one entry
reskins every material that shares that texture and a material a variant does not mention keeps the one
it had. Variant 0 is the base, and is normally an empty map.

For Bedrock geometry, where there is only ever one texture to substitute, name the skins directly:

```java
GemRenderModels.skins(SKINS, GEOMETRY, List.of(PLAIN, DESERT, WINTER));
```

Put a variant on an instance with the offset the model hands you:

```java
instance.variant(gltf.variant(drone.liveryIndex()));
```

`gltf.variantCount()` is how many there are, and an index past the end is clamped rather than thrown –
a variant is content, the count changes when a pack is swapped, and a drone briefly wearing skin 0 is a
better failure inside a visual than an exception. The item and armour paths take the same value:
`ItemAppearance.variant(stack, context)` and `ArmorAppearance.variant(entity, stack, slot)`, asked per
stack, so a gun can wear one camo in the hand and another in the inventory.

**What it costs.** One addition in the vertex shader and 8 bytes on the instance. No second sampler, no
second material, no second draw: nine copies of a model in three skins is one draw and one palette.

**The ceiling is the sheet.** Every variant is a full copy of the model's textures, and the packer
declines rather than exceeding 4096 pixels – it logs what did not fit and the model imports unatlased.
A 64x64 texture leaves room for dozens; a model whose sheet is already 1028x3084 has room for three. A
PBR sheet is three times as tall before any of this, so variants tile across it rather than down.

**Every variant's textures have to be the same size as variant 0's**, because they share one packed
layout. A skin of another size throws at import with both sizes named. That is deliberate: dropping it
quietly would ship a model wearing the wrong skin, and that is a content bug which never announces
itself.

### Reloads and failures

**Hold the handle, not the model.** A `GemRenderGltfModel` owns a stitched atlas texture and a range
of the shared morph buffer, both freed on reload. The handle is stable across reloads and what is
behind it is replaced, so a stored handle can never hand you a disposed model. A stored
`GemRenderGltfModel` can.

An import that throws does not propagate: you get `null`, the failure is cached so a broken file is
not re-parsed every frame, and the reason is logged once. A reload retries everything that failed,
because that is the moment the answer can have changed.

**Imports run on a small pool of loader threads, and nothing on them touches the GPU.** Parsing,
material baking, atlas stitching and mesh building are arithmetic; the texture registrations route
themselves through `RenderSystem.recordRenderCall`, and the vertex upload happens on the render
thread on the model's first draw. A `Builder` passed to `GemRenderModels.built` runs there too, so it
must build geometry and nothing else — no GL, no render state, no `Minecraft` field that is only safe
on the render thread.

A reload re-imports everything wanted in parallel and does not wait: the reload finishes, and the
models arrive over the next few frames. Nothing needs handling for that beyond the `null` above.

### The block cache

Compressing a stitched atlas to BC7 is the single most expensive thing an import does — 153 ms for a
1028x3084 sheet — and it is perfectly reproducible, so the blocks are kept in `.gemrender/blocks`
under the instance directory and the encode only ever happens on the launch that first sees a given
sheet. The same atlas costs **16 ms** on every launch after that.

Entries are named by a hash of the pixels, so nothing has to be invalidated: a pack that changes a
texture stitches a different sheet and asks a different question. The directory is capped at 512 MB
and evicts least-recently-used; `-Dgemrender.blockcache=<MB>` changes the cap, and `0` switches the
cache off. Deleting the directory is always safe.

---

## 2. Draw it on a block entity

Not a vanilla `BlockEntityRenderer`. You register a Flywheel `BlockEntityVisualizer`, which builds a
*visual* once and then updates instance data per frame instead of re-submitting geometry.

### Registering the visualizer

`SimpleBlockEntityVisualizer.builder(...).apply()` calls `VisualizerRegistry.setVisualizer` for you,
so do not call it a second time yourself. `skipVanillaRender` is how you stop the vanilla renderer
drawing the same block on top.

```java
@EventBusSubscriber(modid = "mymod", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class MyVisualizers {
    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> SimpleBlockEntityVisualizer
                .builder(MyBlockEntities.DRILL.get())
                .factory(DrillVisual::new)
                // The visual draws the whole machine, so the vanilla renderer must not.
                .skipVanillaRender(be -> true)
                .apply());
    }
}
```

### The visual

Extend `AbstractBlockEntityVisual<T>` and implement `SimpleDynamicVisual`. The base class gives you
`pos` (world), `visualPos` (already `pos.subtract(renderOrigin)`, which is the one you want for the
instance transform), `blockState` and `relight(...)`.

```java
public class DrillVisual extends AbstractBlockEntityVisual<DrillBlockEntity>
        implements SimpleDynamicVisual {

    private GemRenderGltfModel gltf;
    private AnimationPhase phase;
    private GemRenderInstance instance;

    public DrillVisual(VisualizationContext ctx, DrillBlockEntity be, float partialTick) {
        super(ctx, be, partialTick);

        // Nothing else: the model may still be importing. See below.
    }

    /** Takes an instance once the model is in. False while it is not. */
    private boolean acquire() {
        gltf = MyModels.DRILL_MODEL.get();
        if (gltf == null) {
            return false;
        }

        // Seeded off the block position, so this machine is at the same point in its
        // cycle after a restart, on another client, and after the chunk reloads.
        phase = AnimationPhase.scattered(gltf.animationOrAny("run"), pos.asLong());

        instance = instancerProvider()
                .instancer(GemRenderInstanceTypes.SKINNED, gltf.model())
                .createInstance();

        instance.pose.translation(visualPos.getX(), visualPos.getY(), visualPos.getZ());
        instance.colorArgb(0xFFFFFFFF);
        // Per instance, because the per-vertex light attribute carries joint indices.
        relight(instance);
        instance.setChanged();
        return true;
    }

    @Override
    public void beginFrame(Context ctx) {
        if (instance == null && !acquire()) {
            return;
        }

        float seconds = (level.getGameTime() + ctx.partialTick()) / 20.0f;

        // One lookup per instance, one evaluation per distinct instant across the whole
        // frame: machines at the same point in the same clip share a palette.
        PoseCache.Pose pose = PoseCache.getInstance()
                .pose(gltf.layout(), gltf.bounds(), gltf.morphs(),
                        phase.clip(), phase.timeAt(seconds));

        if (instance.boneBase != pose.boneBase()
                || instance.morphBase != pose.morphBase()
                || !instance.boneSphere.equals(pose.sphere())) {
            instance.boneBase = pose.boneBase();
            instance.morphBase = pose.morphBase();
            instance.boneSphere.set(pose.sphere());
            // A machine at rest resolves to the same shared pose every frame and so
            // never re-uploads. Keep that property: only setChanged on a real change.
            instance.setChanged();
        }
    }

    @Override
    public void updateLight(float partialTick) {
        if (instance != null) {
            relight(instance);
        }
    }

    @Override
    protected void _delete() {
        if (instance != null) {
            instance.delete();
        }
    }
}
```

**Do not resolve the model in the constructor.** Imports run on loader threads, so a visual built while
its asset is still importing gets `null` – and a visual that gave up there draws nothing for the rest of
its life, because nothing rebuilds it until the next resource reload. Asking the handle each frame until
it answers is a field read and a generation compare once the model is in. `GemRenderEntityVisual` in the
next section does this for you.

**Flywheel never culls your visual for you.** `AbstractBlockEntityVisual.isVisible(frustum)` and
`doDistanceLimitThisFrame(ctx)` look like framework hooks and are not — nothing in Flywheel calls
either, as its own javadoc says ("You may optionally do this check"). They are helpers your
`beginFrame` has to apply, so a visual that *overrides* `isVisible` and never calls it has written dead
code, and one that never mentions it updates every model in the level every frame, behind you included.

```java
@Override
public void beginFrame(Context ctx) {
    if (!isVisible(ctx.frustum()) || doDistanceLimitThisFrame(ctx)) {
        return;
    }
    ...
}
```

And override `isVisible` whenever the model is bigger than the block: the default is a sphere around
**one block**, so a machine that reaches past its controller stops updating — freezing mid-animation
with most of it still on screen — as soon as that one block leaves the frustum. `PosedBound.test` is
that test, against the bound `PoseCache.Pose.sphere()` gave you for the pose you last drew:

```java
@Override
public boolean isVisible(FrustumIntersection frustum) {
    return super.isVisible(frustum) || PosedBound.test(frustum, instance.pose, lastSphere);
}
```

Union, not replacement — the bound is last frame's. `GemRenderEntityVisual` does all of this for you on
the entity path, where the default is the entity's hitbox and the mismatch is the same one.

**You do not manage the buffers.** Uploading the bone palette, binding it to texture unit 10, binding
the morph deltas to unit 11 and clearing the pose cache all happen once a frame before any Flywheel
draw. You never call `BoneBuffer.uploadAndBind()` or `PoseCache.endFrame()` yourself.

### The four fields

A `GemRenderInstance` has five things a consumer sets, and only four of them matter for a model with one
skin. Three fail loudly, one fails quietly – which is why it is worth naming – and one has a default
that is simply correct.

| Field | What it is | If you forget it |
|---|---|---|
| `pose` | Model to world transform, applied after skinning. Use `visualPos`, not `pos` | The model renders at the render origin, typically thousands of blocks away |
| `light` | Packed lightmap, per instance. Set it with `relight(instance)` | The model is black. Per-vertex light is unavailable, it carries joint indices |
| `boneBase`, `morphBase` | Offsets into the shared buffers, from `PoseCache.Pose`. Never compute these yourself | Every copy shows another machine's pose, or the rest pose forever |
| `boneSphere` | Bounding sphere of the *posed* model, also from `PoseCache.Pose` | **Geometry vanishes near the edge of the screen.** The default is a deliberately small one-block sphere, so the mistake is visible rather than silently disabling culling forever |
| `uvOffset` | Which variant the copy wears, from `gltf.variant(i)`. Only for a model that declares any | The copy wears variant 0, which is the right default and the only value a model without variants has |

**Flywheel's own bounding sphere is unusable.** Do not fall back to `gltf.model().boundingSphere()`.
Flywheel builds it from every primitive's raw vertices heaped into one space, and a GemRender model's
primitives each live in their own space, so it does not bound the assembled model even at rest. On
the test radar it is centred twenty-two blocks away from the model.

### Animation phase

An `AnimationPhase` is a value, not a ticking field. It turns "what time is it" into "where is this
copy in its clip", so a machine holds no animation state, needs no saving or resynchronising, and
survives a chunk reload identically.

- `AnimationPhase.scattered(clip, pos.asLong())` is the one to reach for when placing machines. Every
  block gets its own deterministic place in the cycle.
- `AnimationPhase.of(clip)` runs every copy in lockstep. Cheaper, because they all share one
  evaluated palette, but they move as one.
- `AnimationPhase.REST` holds the pose the file declared.
- `.withSpeed(s)` scales playback; `0` freezes and negative runs backwards.

Do not use a random offset. It looks right on the first frame and wrong on every later one, because
the machine jumps the moment its visual is rebuilt.

### Several clips at once

One clip and one time is the common case. A thing whose parts answer to *different* quantities needs
more than one, and merging them into a single clip cannot work: two independent parameters would need
a clip per pair of values. Pass them as layers instead – same call, arrays instead of scalars:

```java
// A mob whose legs run on distance walked and whose jaws run on an attack timer.
clips[0] = walk.clip();  times[0] = walk.timeAt(entity.walkAnimation.position(partialTick));
clips[1] = bite.clip();  times[1] = bite.timeAt(entity.getAttackAnim(partialTick));
clips[2] = damageStateOrNull;  times[2] = 0.0f;

PoseCache.Pose posed = PoseCache.getInstance()
        .pose(gltf.layout(), gltf.bounds(), gltf.morphs(), clips, times, 0);
```

Layers are applied in order onto one pose, and a `null` layer sits out. Hold the two arrays on the
visual rather than allocating them per frame; a visual is single-threaded with respect to itself, so
they are safe as fields and unsafe as statics.

**Sharing is per layer, and it multiplies.** Two copies collide in the cache only when *every* layer
agrees, so a layer can only ever split the table further. That makes the cost of a layer entirely a
question of how much it varies:

- A layer that hardly ever varies is nearly free. Damage states, variants, a hatch that is open or
  shut: a handful of distinct values across a whole crowd, and clips with no duration at all – see
  `NodeHide` – fall in one time bucket, so the cache separates them by identity rather than instant.
- A layer that varies per copy costs a pose per copy, exactly as a single clip on a continuous clock
  does. Three hundred mobs mid-stride at three hundred different phases is three hundred evaluations
  whether that is one layer or four.

The counting argument in `AnimationPhase.snap` applies unchanged, once per layer.

### Attaching something to a bone

A muzzle flash, a held item, a light, a particle emitter, a child model: all of them need to know where
a bone ended up this frame. The palette is already composed on the CPU, so asking costs a matrix copy.

```java
Matrix4f socket = pose.boneMatrix("muzzle", new Matrix4f());   // model space
socket.mulLocal(instance.pose);                                // relative to the render origin
Vector3f at = socket.transformPosition(new Vector3f());

double worldX = at.x + renderOrigin().getX();
```

`boneMatrix` takes a **node** slot – `NodeTable.slotOf`, `slotOfName`, or the name as above – and not a
slot out of `jointSlots`. A skin's block of the palette holds `global x inverseBind`, which is the
matrix that moves a bound vertex and is *not* where the joint is; every joint has a node slot as well,
and that is the one to ask for. Passing a skin slot throws rather than returning a plausible wrong
transform.

Two things follow from the pose being shared. The instant is the one it was **evaluated** at, which is
its time bucket's representative rather than exactly what you asked for – at most half a quantum out,
and coarser at distance by the same octave `PoseLod` coarsens the model by. And the matrices are valid
for the frame only: the cache hands them back to its pool at the end of it, so keep what you read
rather than the `Pose`.

---

## 3. Draw it on an entity

A mob, a vehicle, a projectile, a drone: everything section 2 does, on something that moves. Same one
draw per model per batch, same skinning in the vertex shader, same shared palettes, same temporal LOD.

Register a visualizer for the entity type, at client setup, on the mod bus:

```java
@SubscribeEvent
static void onClientSetup(FMLClientSetupEvent event) {
    event.enqueueWork(() -> SimpleEntityVisualizer.builder(MyEntities.DRONE.get())
            .factory(DroneVisual::new)
            // The visual draws the whole entity, so the vanilla renderer must not.
            .skipVanillaRender(entity -> true)
            .apply());
}
```

Then extend `GemRenderEntityVisual<T>`, which owns the instance, the transform, the pose and the light:

```java
public class DroneVisual extends GemRenderEntityVisual<Drone> {
    private final GltfAnimation hover;

    public DroneVisual(VisualizationContext ctx, Drone drone, float partialTick) {
        super(ctx, drone, partialTick, MyModels.DRONE);   // the handle, not the model

        addComponent(new ShadowComponent(ctx, drone).radius(0.7f));

        this.hover = ...;
    }

    @Override
    protected void animate(float partialTick, GltfAnimation[] clips, float[] times) {
        clips[0] = hover;
        times[0] = (level.getGameTime() + partialTick) / 20.0f;
    }
}
```

That is the whole of the common case. `animate` writes a clip and an instant into each layer; the
arrays are `layers()` long, are reused between frames, and a layer left `null` sits out. Everything
[section 2 says about several clips at once](#several-clips-at-once) applies here unchanged.

**Where the model goes** is `transform(Matrix4f, float)`, which by default is the entity's interpolated
position and `getYRot()`. Override it for anything else – a mob whose body and head turn separately
usually wants `yBodyRot` here and a head bone driven from the difference, and a vehicle wants pitch and
roll too. The matrix is applied after skinning, so it moves the posed model as a whole.

**Shadow, fire and hitbox are Flywheel's own components** and none is added by default, because a flying
machine wants none of them. `addComponent(new ShadowComponent(...))`, `new FireComponent(...)`,
`new HitboxComponent(...)`.

Three things the base class handles that a hand-written visual gets wrong:

- **The model may not be loaded yet.** Imports run on loader threads, so an entity that comes into view
  during one gets `null` from its handle. The instance is taken on the first frame the model answers,
  not in the constructor. A visual that resolved it once would draw nothing for that entity's whole
  life.
- **Culling is on the model's bound, not the entity's box.** Flywheel tests an entity's own bounding box
  inflated a little, which is right for a model drawn at the size of the thing carrying it. A GemRender
  model is under no obligation to be entity-sized, and a two-block entity wearing a twenty-block machine
  would stop being updated as soon as its box left the frustum – which looks like a model frozen
  mid-animation with most of it still on screen.
- **Lighting is sampled through Flywheel's distance limiter**, so a crowd does not all re-read the
  lightmap on the same frame. The transform is written every frame regardless, because it has to be.

**What a crowd costs.** An entity moves, so its instance is rewritten every frame – a few dozen bytes,
and Flywheel uploads only what changed. The pose is the expensive half, and it is expensive exactly when
a crowd disagrees about the time: three hundred mobs mid-stride at three hundred different points in a
walk cycle is three hundred palette evaluations, because that is genuinely three hundred poses. That is
what `PoseLod` is for, and it is on by default. What is under your control is the instant `animate`
writes; see the counting argument in section 2.

---
## 4. Vehicles, and animation driven by something other than time

Everything above assumes the shape sections 2 and 3 are good at: many copies of one machine, all reading one
clock, so the pose cache collapses them. A vehicle is the other shape. There are a few of them, not a
few thousand, and each one's parts answer to different things – the left tread to how far that side
has travelled, the turret to where its gunner is looking. Nothing is shared between two of them,
ever, and the mechanism section 2 relies on has nothing to collapse.

So there is a second path, and on this shape it is not a little faster, it is a different cost class.
Sixty-four m1a2s with two independent layers, measured in the dev harness:

| path | distinct poses per frame | CPU | fps |
|---|---:|---:|---:|
| skinned, one palette per copy | 64 | 583 us | 1111 |
| **rigid parts** | **2** | **20 us** | 2067 |

It wins by giving up on sharing between copies and exploiting *time* instead: a layer is re-evaluated
only when its own quantised instant moves on, and only the parts that layer drives are rewritten. A
parked tank whose turret is slewing costs the turret.

**The trade, and one limit.** Each part becomes its own instance, so a 112-part tank is 112 instances
rather than one – good for tens of vehicles, wrong for thousands of machines. And the path reads
Bedrock `.geo.json` only; `GemRenderModels.partsHandle` on a `.glb` throws.

### Registering

Same rule as section 1 – declare it in a `static final` so a reload re-imports it.
`GemRenderModels.parts(id)` resolves through the same cache, with the same contract: `null` until it
has loaded. A visual built from a `null` model holds no parts and is not rebuilt when the model
arrives, so gate the visual on the model rather than building one around a `null`.

```java
public static final ModelCache.Handle<GemRenderPartsModel> TANK =
        GemRenderModels.partsHandle(
                ResourceLocation.fromNamespaceAndPath("mymod", "models/m1a2/m1a2.geo.json"));
```

### Layers

A layer is a clip plus the parts it is allowed to move. `drivenBy(clip)` reports which parts the clip
touches and `withAncestors(...)` widens that to everything above them, because moving a turret moves
the gun that hangs off it. Precompute it once – it never changes:

```java
private record Layer(GltfAnimation clip, boolean[] recompute) {
    static Layer of(GemRenderPartsModel model, String clipName) {
        GltfAnimation clip = model.animation(clipName);
        return new Layer(clip, model.withAncestors(model.drivenBy(clip)));
    }
}
```

### Driving a layer with a parameter

`AnimationPhase` answers "where is this copy in its clip" for a clock. `AnimationDrive` answers the
same question for anything else, and hands back the same clip-local seconds, so it drops into the
same slot. Two shapes cover everything a vehicle does:

- **`AnimationDrive.cyclic(clip, unitsPerCycle)`** – a quantity that accumulates without bound and
  means the same thing every cycle. Feed it the odometer directly, in whatever unit you already have:
  a wheel animated as one full turn is `cyclic(spin, 2 * PI * radius)` read off distance travelled.
  It wraps, so reversing runs it backwards and nothing has to be reset.
- **`AnimationDrive.ranged(clip, min, max)`** – a quantity that lives between two stops: a steering
  angle, an elevation, how far a hatch has opened. It scrubs the clip and holds the end frames
  outside the range. Give `max` below `min` to run it the other way. `isAtEnd(v)` is there for
  whatever drives the thing to know it has arrived.

```java
// Two treads on the odometer, a turret on its gunner. The treads share a clip and still move
// independently, because what differs is the parameter, not the animation.
private static final AnimationDrive LEFT_TREAD  = AnimationDrive.cyclic(treadClip,  TREAD_PITCH);
private static final AnimationDrive RIGHT_TREAD = AnimationDrive.cyclic(treadClip,  TREAD_PITCH);
private static final AnimationDrive TURRET      = AnimationDrive.ranged(traverse, -180.0f, 180.0f);
private static final AnimationDrive GUN         = AnimationDrive.ranged(elevate,   -10.0f,  20.0f);
```

Scrubbing a clip rather than rotating one bone is the reason to prefer this over a direct binding:
the parameter can drive any keyframed motion, so a suspension arm that also compresses, or a hatch
that rotates as it slides, costs exactly what a single-axis spin costs.

### The per-frame loop

The shape that produces the 20 us above. Bucket each layer's own instant, skip the ones that have not
moved, union the parts belonging to the ones that have, and evaluate once for the whole copy:

```java
@Override
public void beginFrame(Context ctx) {
    float quantum = PoseCache.getInstance().quantumSeconds();
    Arrays.fill(changed, false);
    boolean any = false;

    // Whatever the block entity or entity already tracks. These are read, never stored here.
    float[] parameters = { be.leftTrackMetres(), be.rightTrackMetres(), be.turretYaw(), be.gunPitch() };

    for (int layer = 0; layer < drives.length; layer++) {
        float wanted = drives[layer].timeAt(parameters[layer]);
        int bucket = Math.round(wanted / quantum);

        clips[layer] = drives[layer].clip();
        times[layer] = bucket * quantum;

        if (bucket != lastBucket[layer]) {
            lastBucket[layer] = bucket;
            any = true;
            or(changed, layers[layer].recompute());
        }
    }

    if (!any) {
        return;
    }

    PartsPose.evaluate(model, clips, times, transforms, changed, scratch);

    for (int part = 0; part < model.partCount(); part++) {
        TransformedInstance instance = instances[part];
        if (instance == null || !changed[part]) {
            continue;
        }
        composed.set(base).mul(transforms[part]);
        instance.pose.set(composed);
        instance.setChanged();
    }
}

private static void or(boolean[] into, boolean[] from) {
    for (int i = 0; i < into.length; i++) {
        into[i] |= from[i];
    }
}
```

`transforms` comes from `model.newTransforms()` and must be seeded once with the rest pose –
`PartsPose.evaluate(model, null, 0.0f, transforms, scratch)` – because parts no layer can reach keep
whatever is in it for good. `Scratch` is one per thread and not thread-safe.

Note what is absent: no elapsed time, no accumulating field, no state in the visual at all beyond
`lastBucket`, which is a cache and can be thrown away. The parameters are read from the thing that
already owns them.

### The one thing the asset has to get right

A bone the partition did not cut above is baked into its part's geometry, and writing its transform
then changes nothing – the motion vanishes silently rather than failing. Any bone a layer needs to
move must be declared in `gemrender:gameplay_bones` so the partition cuts above it. GemRender logs an
error naming the bone and the part it was baked into, which is the first thing to check when a turret
will not turn.

### When the pose is not a clip at all

Everything above scrubs a clip, because a clip is what an asset ships with. A mod that already has an
animation system – a state machine, a blend graph, a script – has something else: a transform per
bone, computed per frame, that no clip describes. Nothing about a bone palette requires a clip, so
that case is a supported one.

Write the bones into a **node state**, which is the same `float[]` a clip's drivers write into, and
compose it yourself:

```java
NodeTable table = gltf.layout().nodeTable();
float[] state = table.newScratch();          // hold this; it is per copy
table.resetToRest(state);                    // anything not written stays at rest

int slot = table.slotOfName("turret");       // -1 for a bone the model does not have
if (slot >= 0 && table.isPosable(slot)) {    // false for a node the file declared as a matrix
    table.setTranslation(state, slot, x, y, z);
    table.setRotation(state, slot, quaternion);
    table.setScale(state, slot, sx, sy, sz);
}

GltfPose.evaluate(gltf.layout(), state, palette, gltf.morphs(), morphBlock, scratch);
gltf.bounds().evaluate(palette, sphere);

instance.boneBase = BoneBuffer.getInstance().addPalette(palette, gltf.jointCount());
instance.boneSphere.set(sphere);
instance.setChanged();
```

`table.restTranslation(slot, axis)` and `restRotation(slot, out)` are there for expressing a pose as
an offset from the rest one, which is what an additive animation system produces.

**This gives up sharing, and that is the whole cost.** `PoseCache` keys on a clip and an instant, and
this has neither, so it is one palette evaluation and one upload per copy per frame — the cost class
[the table above](#3-vehicles-and-animation-driven-by-something-other-than-time) calls "skinned, one
palette per copy". For a few dozen vehicles that is nothing; for a crowd, use a clip. And the two
arrays are yours: compose on one thread and read on another and you will upload a half-written pose,
so either do both on the same thread or publish whole palettes and alternate between two of them.

---

## 5. Rigging a model that has none

Sections 1 to 3 assume the asset arrives with a skeleton in it. A lot of them do not. A mod that has
been drawing a machine for years usually has a folder of `.obj` parts and a hand-written animation:
the geometry and the motion both exist, and nothing joins them.

`RigBuilder` is that join. Declare the bones, hang the meshes on them, and what comes out is an
ordinary `GemRenderGltfModel` – one Flywheel model, one instance a copy, posed through the same bone
palette as an imported one. Nothing downstream can tell the difference.

**Every mesh binds rigidly to exactly one bone.** There is no vertex weighting here, which is what
makes it usable with formats that carry none. A part that has to deform is split into more parts, the
way a hard-surface model is built anyway.

### The order to call things in

Bones first, then `table()`, then clips and meshes. `table()` freezes the skeleton and hands back the
`NodeTable` the driver factories address slots through, so a bone declared after it is an error rather
than a bone nothing can drive.

```java
Map<String, RigGeometry> groups = WavefrontObj.load(MESH);

RigBuilder rig = new RigBuilder("crab");
int body = rig.bone("body", RigBuilder.ROOT, 0, 0, 0);
int claw = rig.bone("claw", body, 0.25f, 0.625f, 0.0625f);

NodeTable table = rig.table();
GltfAnimation wave = GltfAnimation.procedural("wave",
        NodeOscillate.about(table, claw, 1, 0, 0, rad(35), rad(20), 1.0f, 0.0f));

rig.attach(body, groups, "Body")
   .attach(claw, groups, "Claw");
GemRenderGltfModel model = rig.build(material, Map.of("wave", wave));
```

**A bone's translation is its pivot relative to its parent's pivot**, in the frame the meshes were
authored in – the same convention a Bedrock model uses. Attached geometry stays where the artist put
it: `attach` moves each mesh into its bone's frame by the inverse of that bone's rest transform, so a
rig with no clip running draws exactly the model you started with. That inverse is the inverse bind
matrix a glTF skin would have shipped; here the rig knows where every bone rests, so it is derived.

The same geometry may be attached to several bones. Six legs from one pair of meshes is the normal
case, and costs one copy of the vertices per leg.

### Where the meshes come from

`WavefrontObj.load(id)` reads a `.obj` as one `RigGeometry` per named `o`/`g` group. It flips the V
axis and fan-triangulates faces, both matching what NeoForge's own obj loader does with
`flip_v: true`, and it ignores materials – an obj's `.mtl` names a texture through a placeholder the
model json fills in, so the texture belongs in the `Material` you hand `build`.

`RigGeometry` is plain arrays, so anything can produce one: a format of your own, or geometry
generated on the spot.

### Materials

`build(Material, clips)` gives the whole rig one material. `attach(slot, geometry, material)` gives a
part its own; meshes are grouped by material and each group is one draw, so a propeller skinned
separately from its airframe costs two draws however many parts wear each.

The `build(GltfMaterial, clips)` overload is the importers' vocabulary – alpha mode and
two-sidedness rather than Flywheel shaders – and it resolves and owns the texture, decoding a
`.ktx2` and releasing it on reload. Use the `Material` overload for anything `GltfMaterial` cannot
say: a decal at depth-equal, an emissive overlay.

### Drivers: what a bone can be told to do

A clip is an ordered list of `PoseDriver`s. Keyframe channels read out of a file and the procedural
ones below are peers – nothing downstream distinguishes them.

| Driver | Motion | Driven by |
|---|---|---|
| `NodeSpin` | Turns without end at a constant rate | A clock, or `AnimationDrive.cyclic` on an accumulating phase |
| `NodeOscillate` | `base + amplitude * sin(2pi(t/period + phase))` | The limb shape: a walk cycle is one of these per joint at different phases |
| `NodeSwing` | Sweeps linearly between two stops and holds them outside | `AnimationDrive.ranged`; the clip is one unit long so the parameter maps straight on |
| `NodeHide` | Collapses a bone and its whole subtree to nothing | Nothing. Its cycle is zero, so a set of damage states separates by identity, not instant |

Two drivers on one bone give it two axes, applied in the order they were added, the same way
`matrix.rotateY(a).rotateZ(b)` composes. Put the constant part of an angle in the driver's `base`
rather than in the bone's rest rotation whenever a bone turns about more than one axis: rest rotations
compose before every driver, so `Ry(a)Rz(b)` at rest plus drivers on Y and Z gives
`Ry(a)Rz(b)Ry(dy)Rz(dz)`, which is not the `Ry(a+dy)Rz(b+dz)` the rig meant.

**Writing your own driver.** The four above are not a closed set. A motion that is not a sine and not
a sweep – a mandible that stays shut through the first half of a swing and snaps through the second –
is a record implementing `PoseDriver`, and `NodeRotation.compose` is the public helper that writes a
rotation correctly onto whatever the drivers before it left in the pose. Two rules, both from
`PoseDriver`'s own contract: be a pure function of the time argument, and have value equality. A
driver that reaches per-copy state through a field breaks sharing silently – the copy that lands in
its bucket gets somebody else's answer.

### Registering a built model

There is no file to import, so `GemRenderModels.built(id, builder)` takes the builder instead. Same
cache, same reload, same disposal; the id is a name rather than a path, and has to be stable and
unique because it is what the cache keys on. Hold the handle in a `static final`, or declare every one
of them at client setup, for the reason section 1 gives: the builder runs on the thread that first
asks, and a rig built lazily is an obj parse on a Flywheel task thread mid-frame.

```java
ModelCache.Handle<GemRenderGltfModel> handle = GemRenderModels.built(
        ResourceLocation.fromNamespaceAndPath("mymod", "rig/crab"),
        id -> buildCrab());
```

The first builder registered for an id wins, so declaring a handle from two places is a no-op rather
than a race.

### When not to use it

If the asset already has a skeleton, import it – a glTF or a `.geo.json` carries the rig, the
materials and the clips, and none of it has to be restated in code. `RigBuilder` is for the case where
restating it in code is the only option, and for that case it is the difference between a mob costing
one instance and a mob costing one per moving part.

---

## 6. Items, armour and the hand

The same models, drawn where vanilla draws items: in a hand, in an inventory, on the ground, in a
frame, on a head, and worn as armour. It is the same retained path as everywhere else -- geometry
uploaded once, skinning in the vertex shader, one instanced draw per model -- not a CPU mirror of it.

> Earlier versions of this document said this could not work. What follows is what changed.

### Why it looked structural

Three things are true and none of them is the obstacle they appeared to be:

- `VisualizationManager.get(...)` takes a `LevelAccessor`, so there is no manager, instancer or visual
  outside a level. **GemRender therefore owns the draw here instead of Flywheel** -- its own program,
  its own vertex arrays, its own instance buffers.
- The palette is bound from Flywheel's `DrawManager.render`, which runs only in the level pass. So this
  path binds its own (`BoneBuffer.direct()`), on the same texture unit, immediately before its draws.
- Instance transforms are relative to `renderOrigin()` and consumed by Flywheel's view and projection
  uniforms. Here the caller's `PoseStack` rides on the instance and the camera matrices are read at
  flush time.

What none of that touches is the part worth keeping. `skin_lbs.glsl` and `morph.glsl` are concatenated
into this path's vertex shader from the same shipped files Flywheel `#include`s, so a machine in a
hotbar is skinned by the code that skins it in the world, and neither can be quietly forked.

### Drawing an item

Two pieces, both on your mod.

**Build the renderer and give it a name.** Same call on every version:

```java
public static final ResourceLocation RIFLE = ResourceLocation.fromNamespaceAndPath("mymod", "rifle");

GemRenderItemRenderer.register(RIFLE, GemRenderItemRenderer.of(MY_MODEL.get(),
        MY_MODEL.get().animation("idle")));
```

**Then let the item claim it**, and this is one of the two places the version matters. Vanilla changed
which half of the claim is Java and which is data.

*Before 26.1*, an item model JSON inheriting `builtin/entity` is what makes a baked model report
`isCustomRenderer()` and route here at all -- without it nothing below is ever called and the item
draws as a missing model -- and the item names the renderer from client code:

```json
{ "parent": "builtin/entity", "gui_light": "side" }
```

```java
@Override
public void initializeClient(Consumer<IClientItemExtensions> consumer) {
    consumer.accept(new IClientItemExtensions() {
        @Override
        public BlockEntityWithoutLevelRenderer getCustomRenderer() {
            return GemRenderItemRenderer.get(RIFLE);
        }
    });
}
```

`gui_light: side` asks for three-dimensional item lighting rather than the flat lighting a sprite gets.
A model that looks correct in the world and flat in the inventory is this line.

*On 26.1* there is no `getCustomRenderer` and no `builtin/entity`. The item model names its renderer in
data and the item needs no client code at all:

```json
// assets/mymod/items/rifle.json
{
  "model": {
    "type": "minecraft:special",
    "base": "mymod:item/rifle_base",
    "model": { "type": "gemrender:model", "key": "mymod:rifle" }
  }
}
```

`base` is an ordinary item model, used only for the display transforms and the particle texture; the
geometry drawn is GemRender's. `key` is the name passed to `register`.

That one renderer covers **every** item context, on every version. Vanilla hands the display context to
the same hook, so in-hand, first person, the ground, the inventory, an item frame and a head are all
served by it.

### Per-context models, clips and placement

`GemRenderItemRenderer.of` is the fixed case. The general one is `ItemAppearance`, which asks your mod
four questions per copy, all of them functions of the stack **and** the `ItemDisplayContext`:

| Method | What it decides |
|---|---|
| `model` | which asset, or `null` to draw nothing |
| `clip` | which animation, or `null` for the model at rest |
| `seconds` | where in the clip this copy is |
| `transform` | how it sits, on top of vanilla's own item transform |
| `variant` | which of the model's variant skins this copy wears |
| `tint` | a colour multiplied over the model |

(Six questions, not four -- the heading is older than `variant`.)

That shape is deliberate, and it is what a real item model needs: a gun is a different mesh in the hand
than in the inventory, is held differently in first person than in third, and animates on a reload
timer rather than the world clock. `variant` is asked per (stack, context) like the rest, so the same
sheet and the same batch cover a chest full of differently painted guns -- still one draw.

`seconds` **must be a pure function of things that do not change within a frame.** Two calls for the
same stack in one frame that return different instants make the copy flicker -- and returning the same
instant is also what lets a chest of thirty-six identical items share one palette.

**`transform`'s origin is the CENTRE of the item cell, not a corner.** Vanilla's own convention is the
corner -- a vanilla item is a block model living in `[0,1]^3`, and every version translates by
`(-0.5, -0.5, -0.5)` after the display transform and before it calls a custom renderer. GemRender undoes
that, so a glTF's own origin is the anchor and one asset sits the same way on all three versions. So an
implementation that scales the model to fit within half a unit of the origin -- and moves the model's
centre of mass there if the asset did not put it there -- fills the cell and nothing more. Scaling is
not fitting: a bounding sphere that is not centred on the model's own origin still hangs out of the
cell after the most careful `scale`.

Fitting inside the cell matters on 26.1 and nowhere else. 26.1 renders each distinct item model once
into a slot of an offscreen atlas and blits it, and the slot is **scissored** -- so anything outside
the cell is clipped away there where 1.20.1 and 1.21.1 merely let it overflow. An item that means to
overflow says so in its client item JSON:

```json
{ "oversized_in_gui": true }
```

### Drawing armour

Vanilla's armour hook wants a `HumanoidModel`, so `GemRenderArmorModel` is one, and being one is what
makes it work rather than a formality. `HumanoidArmorLayer` copies the wearer's animated part
transforms onto the model before rendering it, so the six vanilla parts already hold this frame's walk
cycle; those rotations are written onto the glTF's own nodes and the model is posed from them, through
the same external-pose seam a vehicle's turret uses (§4).

```java
private static final GemRenderArmorModel ARMOR = new GemRenderArmorModel(
        (entity, stack, slot) -> switch (slot) {
            case HEAD -> HELMET_MODEL.get();
            case CHEST -> VEST_MODEL.get();
            default -> null;          // nothing on this slot
        });
```

The hook that hands vanilla that model is the **second** place the version matters. It keeps its name
on all three and changes its arguments on 26.1, because the wearer is no longer there to pass.

*Before 26.1:*

```java
@Override
public void initializeClient(Consumer<IClientItemExtensions> consumer) {
    consumer.accept(new IClientItemExtensions() {
        @Override
        public HumanoidModel<?> getHumanoidArmorModel(LivingEntity entity, ItemStack stack,
                EquipmentSlot slot, HumanoidModel<?> original) {
            return ARMOR.prepare(entity, stack, slot);
        }
    });
}
```

*On 26.1* the signature is `getHumanoidArmorModel(ItemStack, EquipmentClientInfo.LayerType, Model)`:
armour is chosen during the submit phase, which is handed the wearer's `HumanoidRenderState` rather
than the wearer, and a layer type rather than a slot. `prepare` is unchanged and still takes all three,
so what a mod writes there is the same call with a `null` entity and the slot it derives from the layer
type.

**`ArmorAppearance`'s `entity` is `null` on 26.1, and only there.** The entity is extracted a phase
earlier and deliberately not carried forward, so there is no truthful way to produce one. Everything on
the stack is still available; a mod that varies a model by the wearer rather than by the item has to
carry what it needs on the item. This is the one behavioural difference in the whole API that a
signature does not show, which is why it is stated here and in the interface's own javadoc.

**One model per slot, not one model with hidden bones.** Vanilla asks four separate times, so a model
returned for two slots is drawn twice; `null` is how a helmet-only item says so.

**A piece is drawn once per `prepare`, and vanilla asks for it more than once.** `HumanoidArmorLayer`
renders the model once per `ArmorMaterial.Layer` -- two for anything dyeable -- again for an armour
trim, and again for the enchantment glint, all through the same `renderToBuffer`, because for a vanilla
model those are the same cubes under different textures. A GemRender model carries its own materials
and is drawn whole, so every call after the first is the same picture again: an enchanted chestplate
would cost two instances and composite any blended geometry twice. `prepare` is what marks a piece as
owing a draw, so it is not optional -- returning this model from `getHumanoidArmorModel` without it
draws nothing at all.

The glTF's node names are matched to the vanilla parts by `head`, `body`, `left_arm`, `right_arm`,
`left_leg`, `right_leg`. Pass a map to the constructor if your exporter called them something else. A
name that matches nothing is ignored rather than rejected -- a helmet has no leg bone.

### Batching, and where it happens

Nothing draws at the moment you submit. Copies join a batch and the batch is drawn when its pass
flushes, so **n copies of one model in one pass are one draw**, and copies of one model at one instant
share a single palette.

| Pass | Contains | Flushed at |
|---|---|---|
| `GUI` | inventories, hotbars, anything through `GuiGraphics`, and any entity rendered into a screen | `GuiGraphics.flush()` |
| `LEVEL` | armour, third-person held, dropped items, frames, block entity renderers | after entities, and after block entities |
| `HAND` | the first-person hand | the tail of the hand render |

Those are vanilla's own flush points, not points of GemRender's choosing, which is what makes deferring
safe: vanilla already batches its item geometry and flushes it exactly where ordering starts to matter.
The three are separate because each has its own projection, and batching across them would draw copies
under the wrong matrices.

**A pass is chosen by where the copy will be drawn, not by who queued it.** Vanilla renders entities
outside the level too -- the player in an inventory screen, the totem-of-undying animation -- through
the same renderers and the same armour layer, and nothing out there will ever flush the level pass. A
copy queued for `LEVEL` while the level render is not running is therefore queued for `GUI` instead,
which is where it will actually be drawn: in the frame it was queued in, inside whatever scissor and
lighting the screen set up for it. Armour on the player in your own inventory is that case, and it is
not one a consuming mod can see coming -- `HumanoidArmorLayer` hands out the same model either way.

### What it costs

Measured on the radar (1468 vertices, three primitives merged to one mesh), as the mean over ~10,000
frames of `-Pdirect=<n> -Pdirectstats`. The GPU figure brackets the **whole** flush -- palette upload,
uniform writes, draws and the state restore -- not only the draw.

| copies, one model | palettes | draws | GPU per flush | CPU per flush |
|---|---|---|---|---|
| 1 | 1 | 1 | 5.5 us | 1.8 us |
| 36 | 1 | 1 | 9.2 us | 1.3 us |
| 576 | 1 | 1 | 100 us | 6.6 us |
| 576, each at its own instant | 576 | **1** | 110 us | 15 us |

The other two passes behave the same way and need no trick, because vanilla does not flush inside
either. Both hands are one flush at the tail of the hand render: three copies there measured one draw,
one palette and one flush per frame. Everything drawn in the level -- armour on every wearer, every
dropped item, every frame -- is one flush after entities, because vanilla's entity loop contains no
flush at all; a dropped stack of more than one draws up to five copies of the model at one instant, so
they share a palette as well.

So a flush costs about **5 us before it draws anything**, and about **0.17 us per additional copy**.
Palette sharing is worth roughly 0.19 us per copy it saves; when it cannot help at all -- 576 copies at
576 different instants, a chest of guns each on its own reload timer -- the draw count still does not
move.

**The GUI needs one extra trick, because vanilla flushes per item.** `GuiGraphics.renderItem` ends with
an unconditional `this.flush()`, once per slot, so honouring every flush would mean a draw per slot --
36 items measured 266 us of GPU per frame against 9 us for the same 36 in one flush.

GemRender skips exactly that one flush -- the one inside `renderItem`, identified by its call site --
and vanilla still makes it, so vanilla's own item geometry is resolved as it always was. Everything
else drains the queue first: a decoration, a label, a scissor change, the tooltip, the frame's final
flush. A run of items with nothing drawn over them accumulates into one batch, so a chest of 36
undamaged, unstacked items is **one draw and one flush**, at the same 9 us as if they had been batched
by hand.

It cannot go further than that, and should not. Vanilla draws the count, durability bar and cooldown
sweep through `RenderType.guiOverlay`, which is `NO_DEPTH_TEST` with a `COLOR_WRITE` mask: those carry
no depth of their own and are correct only because they are painted after the item. Deferring past them
-- flushing once at the end of the screen, say -- puts the model over the durability bar. So an item
that *has* a decoration flushes at its decoration instead of at itself, which costs a flush and keeps
the picture right; only stacks of more than one, damaged items and items on cooldown pay it.

The skip is keyed on that one call site -- the `flush()` at the tail of `GuiGraphics.renderItem` -- and
on nothing else, so a `DirectRenderer.submit(..., DirectPass.GUI)` from your own screen code is drawn
at the next `GuiGraphics.flush()` like anything else. Measured with two real vanilla items drawn
between every copy: 36 models and 72 vanilla items still come out as **one flush and one draw**.

What still costs a flush is an item that draws something over itself -- a decoration, a label, a
cooldown sweep. And one thing to know if you draw with a scissor: an item's own flush is deferred, so
if you enable a scissor immediately after drawing GemRender items and before anything else draws, call
`DirectRenderer.flush(DirectPass.GUI)` first.

### What this path does not do

- **PBR is not applied.** A PBR model's sheet carries its extra bands and the vertex UVs address the
  base one, so what draws is the correct base colour under vanilla's item lighting -- plainer than the
  same model in the world, not wrong.
- **Blended geometry is not sorted.** The world path answers transparency with OIT; here blended
  batches are drawn after opaque ones but in queue order within that, so two blended items can
  composite wrongly against each other. Alpha-masked geometry, which is nearly all of it, is unaffected.
- **Shader packs.** This path draws with its own program, so a pack neither shades it nor breaks it;
  it looks the same under a pack as without one. That is the opposite of the world path's behaviour
  (§9).

## 7. Particles

The same idea as section 1, applied to a different problem. A particle's position, size, colour and
spin are a **closed form of its age**: given where it was born, how fast, and when, the shader can
work out where it is now. So nothing about a particle is written per frame. The CPU writes
forty-eight bytes once, at spawn, and never touches that particle again.

That is what makes the count stop mattering. Three thousand particles cost three thousand cull
invocations and one draw, and zero Java.

### The four pieces

| | |
|---|---|
| `GemRenderParticleTypes` | `BILLBOARD`, `MESH`, and `custom(vert, cull)` for your own shader |
| `ParticleStyle` | the curves every particle in a family shares: drag, gravity, how it grows, how it fades, and what it does when it hits a block. Registered once, at most 256 of them |
| `ParticleEmitter` | owns a block of slots and a spawn cursor. Lives as long as the effect does, **not** as long as the visual |
| `ParticlePool` | the Flywheel side: one instance per slot, created in the visual, deleted with it |
| `ContactResponse` | `NONE`, `STOP`, `BOUNCE`, `DIE` — what a particle does when its flight runs into the world |
| `GemRenderParticleTypes` | `BILLBOARD` for camera-facing quads, `MESH` for a model oriented along its own velocity |

### Registering a style

```java
private static final int EXHAUST = ParticleBuffer.getInstance()
        .registerStyle(ParticleStyle.builder()
                .drag(ParticleStyle.dragFromPerTickFactor(0.9f))
                .gravity(-1.6f)
                .size(0.5f, 2.8f)
                .tint(0xFF7A28)
                .alpha(0.75f, 0.4f)
                .cool(0.1f, 0.6f)
                .build());
```

The table holds 256 styles and the whole of it is 20 KiB whether it is full or holds one, so it is
generous on purpose — but it is still finite, and running out throws. A caller that caches a style per
tint, or per tint *and* light level, climbs faster than it looks like it should: be ready to fall back
to a near-enough style rather than let an exception out of a particle spawn.

`dragFromPerTickFactor` and `gravityFromPerTickDelta` exist because most existing particle code is
written as a per-tick loop – `v *= 0.9`, `vy += 0.004`. Those are a geometric series and a constant
acceleration, so they have exact closed forms; the two helpers convert the loop's constants into the
per-second ones the shader wants. **Everything the API takes is per second**, including velocity.
A per-tick delta multiplied by 20 is a per-second one.

### Emitting

The emitter belongs to the effect, because Flywheel destroys and rebuilds every visual when the
render origin moves and the trail must not restart when it does.

```java
public final class ExhaustEffect implements Effect {
    private final ParticleEmitter emitter =
            ParticleEmitter.create(EXHAUST, 512, x, y, z);

    public void emit(Vec3 at, Vec3 velocity) {
        emitter.spawn(at.x, at.y, at.z,
                -velocity.x * 8.0, -velocity.y * 8.0, -velocity.z * 8.0,
                2.5f, 0.6f + random.nextFloat() * 0.4f);
    }
}
```

`spawn` overwrites the oldest slot in the ring, so a pool never fills up and never allocates. A slot
that has never been written reads as a dead particle and is culled.

### Drawing

```java
public final class ExhaustVisual extends AbstractVisual
        implements EffectVisual<ExhaustEffect>, SimpleTickableVisual {
    private final ParticlePool pool;

    public ExhaustVisual(VisualizationContext ctx, ExhaustEffect effect, float partialTick) {
        super(ctx, (Level) effect.level(), partialTick);
        this.pool = new ParticlePool(ctx, effect.emitter(),
                GemRenderParticleTypes.BILLBOARD,
                ParticleModels.additive(TEXTURE));
    }

    @Override
    protected void _delete() {
        pool.delete();
    }
}
```

**Drawing from an atlas instead of a texture of your own.** A particle that is a chip of a block
samples the terrain sheet, and which sprite it samples is fixed when the model is built rather than per
particle — so it is baked into the mesh, and costs no room in the particle layout:

```java
TextureAtlasSprite sprite = ...;
Model chip = ParticleModels.sprite(
        ParticleQuad.ofUv(sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1()),
        InventoryMenu.BLOCK_ATLAS);
```

Cached per sprite, so every chip off the same block shares a model and therefore a draw call.

There is no `beginFrame`. If you find yourself writing one, the thing you want to vary per frame
probably belongs in the style or in the closed form instead.

### Mesh particles

`GemRenderParticleTypes.MESH` takes any Flywheel `Model` instead of the built-in quad and orients it
so **the model's +Y points along its velocity**, with `spin` rolling it about that axis. Debris,
casings, sparks with a length. Everything else – the style, the emitter, the pool – is identical.

### Contact

A particle cannot discover a wall while it flies: it has no state to read, which is the trade that
buys everything else on this page. What it can be is **told in advance**. The trajectory is a closed
form, so the whole arc is knowable the moment the particle is born; sweeping it against the world
once, at spawn, produces two ages that ride in the particle's own slot, and the flight is still a
closed form evaluated in the vertex shader with nothing written per frame.

Say what a contact means on the style:

```java
private static final int RUBBLE = ParticleBuffer.getInstance()
        .registerStyle(ParticleStyle.builder()
                .gravity(20.0f)
                .stopsOnContact()                  // lands and stays, keeping the attitude it hit with
                .build());
```

| | |
|---|---|
| `stopsOnContact()` | comes to rest on the first block and holds there. Rubble, casings, gore |
| `bouncesOnContact(restitution, friction)` | rebounds once, then settles where that rebound lands |
| `diesOnContact()` | vanishes at the wall. Free: it is a particle with a shorter life |
| *(nothing)* | flies through everything, and is never swept |

Then spawn through the level rather than past it:

```java
emitter.spawn(level, x, y, z, vx, vy, vz, life, size);

// A mesh particle has extent, so tell it how much, or it lands half inside the floor.
emitter.spawn(level, x, y, z, vx, vy, vz, life, size, spinPhase, tintScale, 0.35f);
```

**The style decides whether the sweep happens at all.** A style with no response never touches the
world, so adding the level to a spawn call costs nothing until you ask for something.

For a burst in the thousands, hoist the probe. It remembers the blocks it has already looked at, and
a thousand particles leaving one point walk mostly the same ground:

```java
ParticleCollision.Probe probe = LevelContactProbe.of(level);
for (int i = 0; i < 1000; i++) {
    emitter.spawn(probe, x, y, z, vx(i), vy(i), vz(i), life, size, 0.0f, 1.0f, 0.35f);
}
```

`ParticleEmitter` already does this for you within a frame – the convenience overload keeps one probe
for as long as the clock stands still – so the explicit form is for when you want to control the
cache's lifetime yourself. Either way it reads blocks, so **call it from the thread that owns the
level**: a tick or an event handler, never a visual's constructor.

To find out where one ended up – a sound, a decal, a scorch mark, the burst a shattering particle
leaves behind – every `spawn` hands back what its sweep found, so you do not have to predict twice:

```java
ParticleCollision.Contact contact = emitter.spawn(probe, x, y, z, vx, vy, vz, life, size, 0f, 1f, 0.35f);
Vector3f restingPlace = ParticleCollision.positionAt(style, spawn, velocity, contact, contact.restAge(),
        new Vector3f());
```

A `DIE` style records no contact – it shortens the life instead – so for one of those, "it hit
something" is `contact.life() < the life you asked for`, and the moment it hit is `contact.life()`.

**How finely the arc is swept.** Each chord is sized from the acceleration the closed form is applying
*at that point* – the pull, less what drag is already taking back – against the same tenth-of-a-block
sagitta. Measuring it locally rather than once at birth is what makes the sweep hold for a flight of
any length: a particle still accelerating gets short chords for as long as it is, and one at terminal
velocity is travelling in a straight line at a constant speed, so a single chord describes the whole
rest of its flight exactly. Sizing the step once from the speed at birth and then stretching it to fit
gets both halves wrong — and gets them wrong *silently*, because a chord that is crossed at a wildly
varying speed maps its hit fraction to the wrong age, and the particle then freezes wherever the closed
form really is at that age. In the air, usually, above the floor it was told it had reached.

**What prediction cannot do**, all three of which follow from it being a prediction:

- **Geometry that moves after the spawn.** A particle in flight when a wall is built lands where the
  wall was not. A block *mined* mid-flight is the more common case and looks the same.
- **More than one rebound.** Each is a separate arc to predict and to store; past the first, nobody is
  counting.
- **Exactness to better than the chord.** The arc is followed in straight segments sized so that no
  chord cuts more than a tenth of a block off the curve. Contacts land within that.

### Your own particle shader

The stock billboard evaluates one particular set of curves: exponential drag, linear growth, a power-law
fade, a cool toward a floor. When your effect wants something else, do not ask for a field to be added
here — declare your own instance type over the same buffer:

```java
private static final InstanceType<ParticleInstance> FLAME = GemRenderParticleTypes.custom(
        ResourceLocation.fromNamespaceAndPath(MODID, "instance/flame.vert"),
        ResourceLocation.fromNamespaceAndPath(MODID, "instance/cull/flame.glsl"));
```

Those live in `assets/<your namespace>/flywheel/instance/`, and Flywheel finds them because it scans every
namespace. Start by including this one, which gives you the record and style structs and every curve as a
function you may use or ignore:

```glsl
#include "gemrender:particle.glsl"

void flw_instanceVertex(in FlwInstance i) {
    GemRenderParticle p = gemrender_particle(i.particle);
    GemRenderStyle s = gemrender_style(p.style);
    float age = flw_renderSeconds - p.spawnTime;
    ...
}
```

The contract you must keep is small. The cull shader has to set `radius = -1e18` for a particle that
`gemrender_particleAlive` rejects, or dead ring slots will draw. `spawnTime` and `life` mean what they say,
because emitters and `aliveCount` read them. Everything else is yours: `sizeScale`, `spinPhase` and
`tintScale` are three uninterpreted per-particle scalars, and the style's sixteen floats are whatever you
decide — `ParticleStyle.of(float...)` writes a raw block, and the named builder is only the convention the
stock shaders happen to use. WF-Ballistics reuses the two cool fields as a white-out start and span.

You do not have to bind anything. `_gemrender_particles` is bound on every Flywheel program, so a shader in
any namespace can read the buffer.

### What a closed form cannot do

No force that depends on another particle, and nothing that has to react to the world *after* it set
off. Both need the previous frame's state, and a particle here has no state to read: that is the trade
that buys the zero per-frame cost and the identical result on every backend. Wind, drag, gravity,
buoyancy, growth and fade are all fine, because none of them need to know what happened last frame.

Collision used to be on that list and no longer is, but it is worth being clear about why: contact is
not simulated, it is **predicted at spawn** (see [Contact](#contact)) and then evaluated in closed form
like everything else. That is what keeps it free per frame, and it is also exactly why a particle
cannot notice a wall that appeared while it was in the air.

### Shader packs

Under Iris neither of Flywheel's stock backends runs; the compatibility layer supplies an instancing
one instead. Particles keep working, because the whole evaluation is in the instance vertex shader
rather than in a compute pass. What is lost is GPU culling, which the instancing backend does not do
at all – a dead particle there collapses to a zero-size quad instead of being thrown away, which
costs four vertex shader invocations and no fragments.

### Choosing a transparency

This is the only decision on this page that changes your frame rate by more than a rounding error, so
make it deliberately.

`ParticleModels` offers three:

- `additive(texture)` – one pass, order-independent by construction. Right for fire, muzzle flash,
  tracers, sparks. Cannot darken, so at any real density it saturates to white.
- `cutout(texture)` – `OPAQUE` plus an alpha test, so one pass, writes depth, and gets early-Z.
  Keeps dark colours, which additive cannot. Costs hard quad edges instead of feathered ones.
- `translucent(texture)` – `ORDER_INDEPENDENT`. The best-looking of the three and by far the most
  expensive.

#### Only two of the three can be ordered against water

This is not a matter of taste, and it is the one way the cheap choice can look broken rather than
plain. Flywheel draws its instances after entities and **before** vanilla's translucent terrain, so a
particle over a lake is drawn before the lake is.

- `cutout` is right by construction. It is opaque where it draws at all, so it writes depth honestly:
  water behind it is correctly rejected, water in front of it is correctly drawn over it.
- `translucent` is right because `WaterSplit` splits it. The OIT stack is cut at the water surface and
  each half composites on its own side of it.
- **`additive` has no right answer.** It is blended, so writing depth would delete the water behind it
  — a puff-shaped hole with the lake bed showing through, with the hard edges of the quads rather than
  the soft edges of the sprite. It therefore writes none (`WriteMask.COLOR`), which means the water
  pass paints over it instead: an additive glow in front of a water surface is dimmed by it, and a
  bright one over a thick surface disappears. That is a misordering rather than an erasure, and it is
  the better of the two, but it is still wrong.

So: additive is for what a misordering does not matter to — a muzzle flash, a spark, something at the
player's own position — and for anything that has to read correctly over water, pick one of the other
two. A smoke trail is `translucent`; a dust plume is `cutout`.

Blended particle materials write no depth at all, for the same reason. If you want a blended particle
to occlude, you want `cutout`.

For exhaust and dust – many particles, mostly not the thing the player is looking at – use `cutout`.
Push the alpha test up (`ParticleModels.billboard(texture, Transparency.OPAQUE, CutoutShaders.HALF)`)
and let the texture's own alpha carve the silhouette rather than scaling alpha down in the style;
a low `alphaScale` with a high threshold discards everything, and a low threshold gives you visible
squares.

### Cost

Per spawn: one 64-byte write. Per frame, per particle: nothing on the CPU. `instanceWrites=0` – no
instance is rewritten after the frame it was created in – and the only per-frame work left is one
`glBufferSubData` per run of dirty pages, which measures at 1 µs.

**Contact costs only at spawn**, and only for a style that asked for it. A burst of a thousand
colliding particles sweeps in **1.22 ms**, once, on the thread that spawned it; the frame after, they
are the same zero-cost particles as any other. Two things are doing that work:

| | 1 000 colliding spawns |
| --- | --- |
| sweep, walking probe | **1.22 ms** |
| sweep, one `BlockGetter.clip` per segment | 1.84 ms |
| blocks the level was asked about, walking probe | **2 233** |
| blocks the level was asked about, clipping | 46 500 |
| blocks asked about at 2 000 particles | 2 507 (**1.12x**, not 2x) |

The last row is the one that matters for scale: what the level is asked is bounded by the *volume the
burst covers*, not by how many particles are in it, because they all cross the same ground and the
probe remembers it. Doubling the particles costs 12% more lookups. The other lever is the arc itself –
segments are sized by how hard the path is bending **at each point along it**, so the straight parts of
a flight are crossed in one long chord and only the parts that actually arc pay for the arc. A particle
that has reached terminal velocity is accelerating at nothing, and the whole rest of its flight is one
segment however long it lives.

`ParticleSweepScaleTest` is where those numbers come from and will tell you if they move.

In a running game, measured on the same machine at 1920x1080: a volley of 49 explosions throwing about
**1 500 colliding mesh particles** — chunks of real block geometry, not billboards — held **163 fps
against a 169 fps empty-scene baseline**, GPU load unchanged at 8-10%. The spawn frame is the expensive
one and it is the one in that number: 196 models baked and 1 500 arcs swept in a single tick. Every
frame after it is free.

That leaves the GPU, and there the whole cost is which `Transparency` you picked, not how many
particles you have. Measured on one machine (RX 7900 XTX, Mesa 26.2, `indirect` backend) at
846x1020, sampling 200 ticks:

| row | frames/s |
| --- | --- |
| empty scene, one particle | 1926 |
| 3 000, `cutout` | 2167 |
| 3 000, `additive` | 2150 |
| 3 000, `translucent` | **713** |
| 30 000, `cutout` | 2067 |
| 30 000 from 2 000 emitters, `cutout` | 2126 |
| 100 000, `cutout` | 900 |
| 300 000, `cutout` | 333 |
| 300 000, `cutout`, `-PparticleSize=0.15` | 1994 |

**The instancer is never the bottleneck.** 30 000 particles on a one-pass blend are indistinguishable
from an empty scene, and the last two rows are the proof that even 300 000 is not the instancer's
limit: they draw the same 288 000 live particles, the same instances, the same vertices and the same
buffer fetches, and differ only in how many pixels the quads cover. Shrinking them wins 6x.

Every cost on this page is fill. There are exactly two levers on it:

- **How many passes rasterise the geometry.** `ORDER_INDEPENDENT` costs 3x `cutout` at the same 3 000
  particles, because Flywheel's moment-based OIT rasterises three separate times – depth range,
  coefficients, evaluate – into full-resolution `RGBA16F` targets.
- **How many pixels the particles cover.** Size, distance, and how much of the screen the effect
  fills. `-PparticleSize=0.15` took the 3 000 OIT row from 649 to 1838 fps and the 300 000 cutout row
  from 333 to 1994.

Particle count barely enters into either. If a particle effect is costing you frames, it is covering
too many pixels or going through too many passes; adding a budget on the count is treating the wrong
number.

Reproduce and tune any of it with:

```
./gradlew client -PspikeExit=400 -PquickPlay=spike -Ppitch=0 -Pparticles=3000 \
    -PparticleBlend=cutout   # additive | translucent | cutout
    -PparticleSize=0.15      # multiplies every particle's size
    -PparticleEmitters=200   # splits the count across that many emitters
    -PparticleSpacing=0      # blocks between them, 0 stacks them on one spot
```

### The water split taxes order-independent particles

GemRender's own `WaterSplit` resubmits every `ORDER_INDEPENDENT` draw a fourth time, so that
Flywheel's OIT geometry interleaves per pixel with vanilla's translucent terrain **and with the
clouds**. Particles are not
exempt, and they pay it whether or not there is anything to interleave with: `-PwaterSplit=false`
took the 3 000 `translucent` row from 655 to 878 fps and the 30 000 one from 98 to 152 fps – 34% and
56%, in a scene with no water in it at all.

Do not reach for the obvious fixes. Both are worse than they look:

**Excluding particles from the resubmission** would be wrong – a particle in front of a water surface
does have to composite after it – and it would not be particle-specific anyway. `GltfMaterial` gives
every glTF `BLEND` material `ORDER_INDEPENDENT`, so translucent *models* take the identical path, and
the split is load-bearing for them. Frozen-clock A/B on the glass row, `-PwaterColumn=8`, split on
versus off: 56% of the models' pixels differ, by up to 176 of 255. That is not overhead, that is the
feature working.

**Skipping the split when the prepass found nothing** is safe – the same A/B with no water differs on
0.16% of model pixels by at most 6 of 255, which is OIT dithering rather than a rendering change –
but it is worth much less than it sounds. `WaterDepthPrepass` renders `RenderType.translucent()`,
which is the whole vanilla translucent terrain layer and not just water: ice, stained glass, slime,
honey, portals. In a real world some of it is nearly always on screen, so the guard would rarely
fire. The numbers above are close to a best case for it, not a typical one.

Two things do reliably avoid the tax: **Fabulous graphics**, where `modeActive()` is false and the
split never runs at all, and not being on the `ORDER_INDEPENDENT` path in the first place. The second
is the one you control, and the fill numbers above already recommend it on their own.

Leaving the path costs you the ordering, though, and only one of the two exits keeps it: `cutout`
writes depth and is correct against water without the split, while `additive` is neither split nor
depth-writing and is simply drawn under the water. See "Only two of the three can be ordered against
water" above before treating this as a free win.

### Clouds are in the split too

Water is not the only vanilla translucent surface drawn after Flywheel's composite. That composite
writes the closest OIT depth with the depth mask on, and clouds are drawn much later in the frame, so
before this a cloud behind an `ORDER_INDEPENDENT` model was depth-rejected and the model had plain sky
behind it. The cloud surface is folded into the same prepass depth the water uses, so a cloud is now
one more thing a model can be in front of or behind.

It needs no extra pass. The cloud depth rides into the prepass on the full-screen copy that already
seeds it with opaque depth, and the front half of the composite is split into the pixels with a cloud
over them and the pixels without -- the ones without are composited exactly where they always were, and
only the ones with wait until `AFTER_WEATHER`, by which time the cloud is down. What the fold costs is
one more rasterisation of vanilla's cloud mesh, skipped entirely on a frame whose view does not reach
the cloud layer at all.

`-PcloudSplit=false` takes it back out and leaves the water split as it was; `-Pclouds=off` is the
other half of the A/B. With no cloud in the level the two are the same picture to the pixel, measured.

One residual, and it is the same one the water half accepts: there is **one split point per pixel**, so
a fragment that is in front of the water and behind a cloud in the same pixel is composited against the
nearer of the two. Clouds are far and water is not, so the two rarely contend.

### Time resolution

`age` comes from `flw_renderSeconds`, which Flywheel writes as a **32-bit** float of
`(ticks + partialTick) / 20`. That is a shared uniform, not something this instancer chooses, and its
resolution decays with client uptime: about 0.25 ms after an hour, 8 ms after a day, 60 ms after a
week. Particle motion stays correct – `spawnTime` is quantised on exactly the same grid, so `age` is
still exact – but past roughly a day of uninterrupted uptime in one dimension it advances in visible
steps. Every Flywheel shader that animates on `flw_renderSeconds` has the same bound.

`ParticleStyle.FLOATS` and the 12-float record layout are given in `particle.glsl`, which is the
authority – `ParticleMotion` is the same arithmetic in Java, kept in step by `ParticleMotionTest`,
and is there for tests and for CPU-side code that needs to know where a particle is. The buffer is
bound as `RGBA32F`, so both records read as whole `vec4`s: three fetches for a particle and four for
a style, rather than 26 scalar ones. That was done expecting it to matter at high counts and it does
not – it measures inside run-to-run noise at every count up to 300 000, because nothing here is ever
fetch-bound. It is kept for being the simpler shader, not for being the faster one.

One sizing note the harness row makes obvious: a pool is a ring, so if the spawn rate times the mean
lifetime equals the pool size exactly, the ring wraps just as the oldest particle is dying and a few
per cent get overwritten early. `-Pparticles=3000` settles at about 2880 alive for that reason. Size
the pool ten to twenty per cent above `rate x life` if you want the full count on screen.

---

## 8. Ropes, cables and chains

A rope is the same idea as a particle, applied once more: its shape is a **closed form of its two
endpoints**, so the vertex shader can work out where every ring of it goes and the CPU writes only
where the ends are. One rope is one instance of a shared tube mesh. A hundred of them are one draw.

Vanilla does the opposite — `renderLeash` builds a strip of quads into a `VertexConsumer` every frame,
per lead, and none of them batch. That is the cost this replaces.

### What it is for

Leads and leashes, tow cables, power lines, rigging, a winch, a tethered balloon, an anchor chain.
Anything where two things are connected and the connection should be visible. It is **decoration**:
nothing here is simulated, collidable or synced, and there is no rope physics. If you want a rope that
does something, drive its endpoints from whatever already does that thing.

### Drawing one

```java
public final class MooringVisual extends AbstractVisual {
    private final RopeInstance rope;

    public MooringVisual(VisualizationContext ctx, ...) {
        this.rope = ctx.instancerProvider()
                .instancer(GemRenderRopeTypes.ROPE, RopeModels.cutout(CHAIN))
                .createInstance();

        rope.between(x, y, z, x, floor, z)
                .slack(1.01f)
                .radius(0.055f)
                .tiling(y - floor)
                .litUniformly(light)
                .refresh()
                .setChanged();
    }
}
```

Endpoints are relative to the render origin, like every other Flywheel instance. Move them and call
`setChanged()`; that is six floats, and it is the only per-frame cost a rope has.

### The shape

| | |
|---|---|
| `sag(blocks)` | how far it droops below the straight line, at its deepest |
| `slack(ratio)` | the same thing said the other way: 1.0 is taut, 1.2 is a fifth longer than the gap |
| `length(blocks)` | and the same again, as an absolute length of rope |
| `radius(blocks)` | how thick it is |
| `tiling(repeats)` | how many times the texture repeats along it |
| `twist(turns)` | full turns of the cross-section end to end, for a braid or a link |
| `sway(amplitude, frequency, phase, waves)` | a drift across the rope, pinned to zero at both ends |

`slack` and `length` **solve for the sag on the spot**, so call them after setting the endpoints.
The solve is a bisection on arc length and it happens once per change, not once per vertex — the
answer is the same for all several hundred of them.

**They mean nothing on a rope that hangs straight down, and they will not say so.** The sag is along
-Y, so when the two ends share a column it slides points *along* the rope rather than bowing it: the
length barely responds until the curve doubles back, and then jumps. A twenty-block vertical rope asked
for 1% of slack is handed a sag of 5.7 — length-correct, drawn in exactly the same place, and with half
the texture's repeats crammed into the bottom fifth. For anything vertical, including every mooring and
every hanging chain, set `sag` yourself; zero is right for a taut one and the shape is identical anyway.

The curve is a **parabola, not a catenary**, and that is deliberate rather than an approximation taken
for speed. The closed-form catenary is a function of horizontal distance, so it has no value at all
when one endpoint is directly above the other — which is the case a mooring chain is in for its whole
life. The parabola is defined everywhere, is what a uniformly loaded cable actually hangs in, and at
the sag ratios anything in a game uses the two differ by well under a pixel.

Give `sway` a **phase that is a function of something stable** — a block position, an entity id — not a
random. A random looks identical on frame one and jumps every time Flywheel rebuilds the visual, which
it does whenever the render origin moves. Same rule as `AnimationPhase.scattered`.

### Lighting

`light` is the near end and `lightB` the far one, interpolated along the rope. `litUniformly(l)` sets
both. A chain hanging from a lit surface into a dark one is the case this exists for; giving both ends
one value makes a fifty-block rope uniformly bright.

### Tessellation and materials

`RopeModels.solid`, `.cutout` and `.absorbance` take an optional `rings, sides`. Rings is along the
rope — 16 is enough that a rope bent double has no visible flats. Sides is around it, and **4 is what
a vanilla lead looks like**, which is usually what you want; a thick chain seen close wants 6 or 8.

Use `.cutout` for anything whose texture has holes in it, which includes every chain texture. Use
`.absorbance` for a rope hanging in water: it joins the water split's absorbance pass, so it is tinted
and faded by depth instead of drawn flat through a hundred blocks of ocean (§7).

The tube is **capped at both ends**, because Flywheel culls backfaces and an open tube does not read
as an open tube — it reads as a missing face.

### Your own rope shader

Same escape hatch as particles. Declare an instance type over the same layout and include the shipped
curve, so what you draw and what the cull shader tests stay the same thing:

```java
private static final InstanceType<RopeInstance> CABLE = GemRenderRopeTypes.custom(
        ResourceLocation.fromNamespaceAndPath(MOD_ID, "instance/cable.vert"),
        ResourceLocation.fromNamespaceAndPath(MOD_ID, "instance/cull/cable.glsl"));
```

```glsl
#include "gemrender:rope.glsl"
```

`gemrender_ropePoint`, `gemrender_ropeTangent`, `gemrender_ropeFrame` and `gemrender_ropeSway` are the
whole of it. `RopeCurve` is the same construction in Java, for tests and for anything that needs to
know where a rope is without asking the GPU.

### What it costs

Per rope, per frame: nothing, unless an endpoint moved, and then 84 bytes. Per rope, ever: one cull
invocation. The draw is shared with every other rope of the same model.

The one number to watch is `rings x sides x 4` vertices per rope, all of which are transformed whether
the rope is 2 blocks long or 60. At the default that is 288 per rope — comparable to a small model, and
the reason to turn the tessellation *down* for something thin and short rather than up.

## 9. Things that will bite

Failure modes that render something plausible rather than throwing.

### Shader packs disable half the renderer

Under any Iris shader pack, the compatibility layer merges Flywheel's vertex shader into the pack's
and *discards Flywheel's fragment shader entirely*. Consequences:

- **GemRender's own PBR shader does not run.** It cannot; the fragment stage is the pack's.
- **The material data still gets through, if the pack is set up for it.** GemRender registers a
  LabPBR loader with Iris, so a pack whose resource-pack materials are turned on lights the model
  with its own PBR from GemRender's normal and specular maps. Emission is a scalar in LabPBR and a
  colour in glTF, so an emissive strip glows in its albedo's colour.
- **Every pack ships with those materials off.** At stock settings a pack does not read them, and a
  PBR model falls back to base colour – no normal mapping, no roughness, no emissive, a glowing lamp
  as a black plate. Everything baked into the sheet at import survives, so tints and occlusion remain.
- **Parallax occlusion mapping must stay off.** It needs `mc_midTexCoord`, which instanced geometry
  has no attribute for; with it on, models render near-black. This one is not GemRender's to fix and
  happens whether or not GemRender supplies material data.
- **`BLEND` geometry is discarded, not blended.** The layer forces an alpha test of `GREATER 0.5`
  onto every Flywheel draw, so a half-transparent shell disappears rather than compositing badly.

The settings each pack needs, and the line citations behind these four claims, are in the
maintainers' notes.

> When judging a screenshot, check whether a pack was on **and what its material setting is** before
> concluding anything. A flat, unlit, non-emissive render under a stock pack is the expected result,
> not a regression.

### Cost is per distinct pose, not per machine

The only cost in the design that scales with instance count is one palette evaluation per animated
instance per frame. `PoseCache` collapses that: instances of the same model, on the same clip, at the
same quantised instant evaluate once and share a `boneBase`. Sixty-four machines placed at different
times still cost sixty-four evaluations, and that is the correct answer. Sixty-four machines running
in lockstep cost one.

### Do not set changed unnecessarily

The instance struct is 96 bytes and does not dedupe the way palettes do. Compare before assigning, as
in the example above, so a machine holding a shared pose stops re-uploading. On the `indirect` backend
a single instance write costs a whole 32-instance page.

### Constructors run off the render thread

Flywheel builds visuals on its task executor, so a visual constructor can run on any thread and
several can run at once. Do not touch GL there, and do not throw: an exception on a task thread takes
the client down with a stack trace that names none of the responsible code. Return an empty visual
instead, as the example does when the model is null.

---

## Reference

### Where a model can be drawn

Every place vanilla draws a model or an item, and what carries it there. All three versions.

| Target | How | Section |
|---|---|---|
| Block entity in the world | Flywheel `BlockEntityVisualizer` + `GemRenderInstance` | [2](#2-draw-it-on-a-block-entity) |
| Entity in the world | `GemRenderEntityVisual` | [3](#3-draw-it-on-an-entity) |
| Item in an inventory or the HUD | `GemRenderItemRenderer`, GUI pass | [6](#6-items-armour-and-the-hand) |
| Item in the first person | the same, hand pass | 6 |
| Dropped item, item frame, third person, head slot | the same, level pass | 6 |
| Worn armour, on a mob and in a screen | `GemRenderArmorModel` | 6 |
| Entity or item drawn *inside a screen* | rerouted automatically; you do not pick the pass | 6 |
| Particles | `ParticleEmitter` + `ParticlePool` | [7](#7-particles) |
| A connection between two points | `RopeInstance` | [8](#8-ropes-cables-and-chains) |

Two things that are deliberately not on it. **A static block is not a target** -- a GemRender model needs
a block entity, because Flywheel does no static-block instancing and there is no chunk-mesh route.
**A Flywheel visual does not run outside the level render**, so an entity drawn into a screen shows its
vanilla renderer there; its *armour* still comes from GemRender, because armour goes through the item
path and that one is rerouted.

You never choose which pass a copy joins. `GemRenderItemRenderer` picks it from the display context, and
the choice is then overridden by where the copy will actually be flushed -- a level copy submitted while
the level render is not running becomes a GUI copy. That is the whole reason an armour layer, which is
handed the same model in an inventory screen as in the world, needs to know nothing about the difference.

### The types

The types a consumer actually touches.

| Type | Purpose |
|---|---|
| `GemRenderModels` | `handle(id)` to declare an asset, `get(id)` to read it. `partsHandle(id)` for the rigid-part path, `built(id, builder)` for one you assemble yourself |
| `ModelCache.Handle<T>` | Stable reference across reloads. `get()`, `hasFailed()` |
| `GemRenderGltfModel` | The imported asset: `model()`, `layout()`, `bounds()`, `morphs()`, `animations()`, `jointCount()` |
| `GemRenderInstanceTypes.SKINNED` | The instance type to pass to `instancer(...)` |
| `GemRenderInstance` | `pose`, `boneBase`, `morphBase`, `boneSphere`, plus colour and light |
| `PoseCache` | `pose(layout, bounds, morphs, clip, time)`. The only supported way to get a `boneBase`. The `clips[]`/`times[]` overload layers several clips, each at its own instant |
| `AnimationPhase` | `scattered(clip, seed)`, `of(clip)`, `REST`, `timeAt(seconds)` |
| `AnimationDrive` | The same, for a parameter instead of a clock. `cyclic(clip, unitsPerCycle)`, `ranged(clip, min, max)`, `timeAt(parameter)` |
| `GltfAnimation` | A clip. `name()`, `duration()`, `loop(t)` |
| `GemRenderPartsModel` | The rigid-part path (section 3). `animation(name)`, `partCount()`, `parts()`, `newTransforms()`, `drivenBy(clip)`, `withAncestors(driven)` |
| `PartsPose` | `evaluate(model, clips, times, out, only, scratch)`. Several layers at once, each at its own instant |
| `RigBuilder` | A skeleton declared in code (section 4). `bone(...)`, `table()`, `attach(...)`, `build(material, clips)` |
| `RigGeometry` | One mesh on its way into a rig: positions, normals, texture coordinates, indices |
| `WavefrontObj` | `load(id)` reads a `.obj` as one `RigGeometry` per named group |
| `NodeSpin`, `NodeOscillate`, `NodeSwing`, `NodeHide` | Procedural drivers: turn, rock, sweep, disappear |
| `NodeRotation` | `compose(...)` and `offsetOf(...)`, for writing a `PoseDriver` of your own |
| `ParticleStyle` | the curves a family of particles shares (section 6). `builder()`, `dragFromPerTickFactor`, `gravityFromPerTickDelta` |
| `ParticleBuffer` | `registerStyle(style)` returns the index every emitter of that family passes |
| `ParticleEmitter` | `create(style, capacity, x, y, z)`, `spawn(...)`, `isIdle()`, `close()`. Held by the effect, not the visual |
| `ParticlePool` | `new ParticlePool(ctx, emitter, type, model)` in the visual, `delete()` with it |
| `GemRenderParticleTypes` | `BILLBOARD` and `MESH`, the two instance types to pass to `ParticlePool` |
| `ParticleModels` | `additive`, `cutout` and `translucent` billboards; `cutout` is the cheap one, `additive` the one that cannot be ordered against water |
| `ParticleMotion` | the closed form in Java, for tests and for CPU-side code that needs a particle's position |
| `GemRenderRopeTypes` | `ROPE`, the instance type to pass to `instancer(...)`, and `custom(vert, cull)` (section 8) |
| `RopeInstance` | one rope: `between`, `sag`/`slack`/`length`, `radius`, `tiling`, `twist`, `sway`, `lightB`, `refresh` |
| `RopeModels` | `solid`, `cutout` and `absorbance` tubes, each taking an optional `rings, sides` |
| `RopeCurve` | the curve in Java: `point`, `tangent`, `frame`, `length`, `sagForLength`, `sagForSlack`, `sphere` |
| `GemRenderItemRenderer` | items, in every context (section 6). `of(model, clip)`, `register(id, renderer)`, `get(id)`, `animates(stack, context)` |
| `ItemAppearance` | what a stack looks like per context: `model`, `clip`, `seconds`, `transform`, `variant`, `tint` |
| `GemRenderArmorModel` | worn armour. `prepare(entity, stack, slot)`, `DEFAULT_BONES` |
| `ArmorAppearance` | which model a piece draws per slot: `model`, `variant`, `tint` |
| `VariantUv` | one model's variant skins. `NONE`, and `GemRenderGltfModel.variant(i)` |

### The SKINNED instance layout

One hundred and four bytes. Worth knowing only if you are writing your own instance type against the
same shaders; the writer must match exactly, because it writes raw memory and a mismatch produces wrong
geometry rather than an error.

| Offset | Size | Field | Representation |
|---:|---:|---|---|
| 0 | 4 | `color` | 4 x normalised unsigned byte |
| 4 | 4 | `light` | 2 x unsigned short |
| 8 | 4 | `boneBase` | unsigned int, in matrices |
| 12 | 4 | `morphBase` | unsigned int, in floats |
| 16 | 16 | `boneSphere` | vec4, centre and radius |
| 32 | 64 | `pose` | mat4 |
| 96 | 8 | `uvOffset` | vec2, the variant's tile |

Note what is absent: **overlay**. Stock instance types carry one because their vertex shaders assign
it over the per-vertex value, and GemRender spends the per-vertex overlay on the morph set index, so
there is nothing to overlay onto.

### Version differences

The complete list, read off the three built jars rather than remembered. Everything not named here has
the same public signature on 1.21.1, 1.20.1 and 26.1 -- every type in the table above, the whole of
sections 1 to 5 and 7, and the two appearance interfaces in section 6.

| | 1.20.1 and 1.21.1 | 26.1 |
|---|---|---|
| An item claims its renderer | `IClientItemExtensions.getCustomRenderer()`, plus an item model inheriting `builtin/entity` | an item model of `"type": "minecraft:special"` naming `gemrender:model` and a `key`; no client code |
| `GemRenderItemRenderer` is a | `BlockEntityWithoutLevelRenderer` | `SpecialModelRenderer<ItemStack>` |
| Armour hook | `getHumanoidArmorModel(LivingEntity, ItemStack, EquipmentSlot, HumanoidModel)` | `getHumanoidArmorModel(ItemStack, EquipmentClientInfo.LayerType, Model)` |
| `GemRenderArmorModel` is a | `HumanoidModel<LivingEntity>` | `HumanoidModel<HumanoidRenderState>` |
| `ArmorAppearance`'s `entity` | the wearer | `null` -- see section 6 |
| A model that overflows its item cell in the GUI | overflows the slot | is clipped, unless the client item JSON says `"oversized_in_gui": true` |

Three capabilities are absent on a version rather than different, and none of them is API:

- **Iris/shader-pack support is 1.21.1 only.** `iris-flw-compat` is pinned to one Sodium and Iris pair
  for that version; there is no 1.20.1 or 26.1 equivalent to bind against.
- **KTX2/BC7 model textures need LWJGL 3.3.2.** 1.20.1 ships 3.3.1, so the feature is compiled out
  there and model textures fall back to PNG. On 26.1 the atlas is compressed but the upload has no
  path yet, so a model whose sheet gets compressed renders as the missing texture -- keep assets under
  the compression threshold on 26.1 for now.
- **The water split, and with it the cloud half, is off on 26.1.** Translucent models there occlude
  the vanilla translucent surfaces behind them, as they did everywhere before the split.
