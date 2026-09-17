uniform samplerBuffer _gemrender_particles;

const int GEMRENDER_STYLE_FLOATS = 20;
const int GEMRENDER_MAX_STYLES = 256;
const int GEMRENDER_PARTICLE_FLOATS = 16;
const int GEMRENDER_STYLE_TEXELS = GEMRENDER_STYLE_FLOATS / 4;
const int GEMRENDER_PARTICLE_TEXELS = GEMRENDER_PARTICLE_FLOATS / 4;
const int GEMRENDER_PARTICLE_BASE = GEMRENDER_STYLE_TEXELS * GEMRENDER_MAX_STYLES;

struct GemRenderParticle {
    vec3 spawnPos;
    float spawnTime;
    vec3 spawnVelocity;
    float life;
    float style;
    float sizeScale;
    float spinPhase;
    float tintScale;
// Where this particle's flight runs into the world, worked out on the CPU at spawn -- see
// ParticleCollision. contactAge is GemRender's NEVER (1e30) for a particle that hits nothing, which is
// what lets the arithmetic below be unconditional rather than branchy.
    float contactAge;
    float contactNormal;
    float restAge;
};

struct GemRenderStyle {
    float drag;
    float dragY;
    float gravity;
    float size0;
    float sizeRate;
    vec3 tint;
    float alphaScale;
    float alphaFalloff;
    float coolFloor;
    float coolSpan;
    float spinRate;
    vec2 light;
    float fadeIn;
    float restitution;
    float friction;
};

GemRenderParticle gemrender_particle(uint slot) {
    int base = GEMRENDER_PARTICLE_BASE + int(slot) * GEMRENDER_PARTICLE_TEXELS;

    vec4 a = texelFetch(_gemrender_particles, base);
    vec4 b = texelFetch(_gemrender_particles, base + 1);
    vec4 c = texelFetch(_gemrender_particles, base + 2);
    vec4 d = texelFetch(_gemrender_particles, base + 3);

    GemRenderParticle p;
    p.spawnPos = a.xyz;
    p.spawnTime = a.w;
    p.spawnVelocity = b.xyz;
    p.life = b.w;
    p.style = c.x;
    p.sizeScale = c.y;
    p.spinPhase = c.z;
    p.tintScale = c.w;
    p.contactAge = d.x;
    p.contactNormal = d.y;
    p.restAge = d.z;
    return p;
}

GemRenderStyle gemrender_style(float index) {
    int base = int(index) * GEMRENDER_STYLE_TEXELS;

    vec4 a = texelFetch(_gemrender_particles, base);
    vec4 b = texelFetch(_gemrender_particles, base + 1);
    vec4 c = texelFetch(_gemrender_particles, base + 2);
    vec4 d = texelFetch(_gemrender_particles, base + 3);
    vec4 e = texelFetch(_gemrender_particles, base + 4);

    GemRenderStyle s;
    s.drag = a.x;
    s.gravity = a.y;
    s.size0 = a.z;
    s.sizeRate = a.w;
    s.tint = b.xyz;
    s.alphaScale = b.w;
    s.alphaFalloff = c.x;
    s.coolFloor = c.y;
    s.coolSpan = c.z;
    s.spinRate = c.w;
    s.light = d.xy;
    s.dragY = d.z;
    s.fadeIn = d.w;
    s.restitution = e.x;
    s.friction = e.y;
    // e.z is the style's ContactResponse, which only the CPU reads: what it decides is already baked into
    // the two ages and the life the particle carries.
    return s;
}

vec3 _gemrender_drag(in GemRenderStyle s) {
    return vec3(s.drag, s.dragY, s.drag);
}

// One leg of a flight: where something that started at pos0 with vel0 is t seconds later. A particle is one
// of these before it touches anything and a second one after it rebounds, which is the whole of what contact
// does to the closed form.
vec3 _gemrender_flightPos(vec3 pos0, vec3 vel0, in GemRenderStyle s, float t) {
    vec3 d = _gemrender_drag(s);
    vec3 g = vec3(0.0, -s.gravity, 0.0);

    vec3 safe = max(d, vec3(1e-4));
    vec3 terminal = g / safe;
    vec3 travel = (1.0 - exp(-safe * t)) / safe;

    vec3 dragged = pos0 + (vel0 - terminal) * travel + terminal * t;
    vec3 free = pos0 + vel0 * t + 0.5 * g * t * t;

    return mix(free, dragged, greaterThan(d, vec3(1e-4)));
}

vec3 _gemrender_flightVel(vec3 vel0, in GemRenderStyle s, float t) {
    vec3 d = _gemrender_drag(s);
    vec3 g = vec3(0.0, -s.gravity, 0.0);

    vec3 safe = max(d, vec3(1e-4));
    vec3 terminal = g / safe;

    vec3 dragged = (vel0 - terminal) * exp(-safe * t) + terminal;
    vec3 free = vel0 + g * t;

    return mix(free, dragged, greaterThan(d, vec3(1e-4)));
}

// The six block faces, packed as one float: the low bit is the sign, the rest is the axis.
vec3 _gemrender_normal(float encoded) {
    int face = int(encoded);
    int axis = face >> 1;
    float facing = (face & 1) == 1 ? 1.0 : -1.0;
    return vec3(axis == 0 ? facing : 0.0, axis == 1 ? facing : 0.0, axis == 2 ? facing : 0.0);
}

// The velocity coming out of a surface: what went into it returns scaled by restitution, what ran along it
// keeps friction. A velocity already leaving is only slowed -- flipping it would drive the particle back
// into the block it just left.
vec3 _gemrender_reflect(vec3 velocity, float encoded, in GemRenderStyle s) {
    vec3 n = _gemrender_normal(encoded);
    float along = dot(velocity, n);
    float bounced = along < 0.0 ? -s.restitution * along : along;
    return (velocity - along * n) * s.friction + bounced * n;
}

bool gemrender_particleAlive(in GemRenderParticle p, float age) {
    return p.life > 0.0 && age >= 0.0 && age < p.life;
}

float gemrender_particleUnitAge(in GemRenderParticle p, float age) {
    return clamp(age / max(p.life, 1e-6), 0.0, 1.0);
}

// A particle that has settled keeps the velocity it arrived with rather than reporting zero: a mesh particle
// is oriented along this vector, and rubble that landed should lie the way it hit rather than snap upright
// the instant it stops.
vec3 gemrender_particleVelocity(in GemRenderParticle p, in GemRenderStyle s, float age) {
    float flight = min(age, p.contactAge);
    vec3 v = _gemrender_flightVel(p.spawnVelocity, s, flight);

    if (age <= p.contactAge || p.restAge <= p.contactAge) {
        return v;
    }

    float settled = max(0.0, min(age, p.restAge) - p.contactAge);
    return _gemrender_flightVel(_gemrender_reflect(v, p.contactNormal, s), s, settled);
}

vec3 gemrender_particlePosition(in GemRenderParticle p, in GemRenderStyle s, float age) {
    float flight = min(age, p.contactAge);
    vec3 at = _gemrender_flightPos(p.spawnPos, p.spawnVelocity, s, flight);

    if (age <= p.contactAge) {
        return at;
    }

    // Past the contact the particle is on its second leg, and past restAge it is on neither: the clamp
    // freezes it where that leg ended, which is a particle lying on the floor.
    vec3 rebound = _gemrender_reflect(_gemrender_flightVel(p.spawnVelocity, s, p.contactAge),
                                      p.contactNormal, s);
    float settled = max(0.0, min(age, p.restAge) - p.contactAge);
    return _gemrender_flightPos(at, rebound, s, settled);
}

float gemrender_particleSize(in GemRenderParticle p, in GemRenderStyle s, float unitAge) {
    return p.sizeScale * (s.size0 + s.sizeRate * unitAge);
}

vec4 gemrender_particleColor(in GemRenderParticle p, in GemRenderStyle s, float unitAge) {
    float cool = s.coolFloor
    + (1.0 - s.coolFloor) * (1.0 - min(unitAge / max(s.coolSpan, 1e-6), 1.0));
    float alpha = s.alphaScale * pow(1.0 - unitAge, s.alphaFalloff);
    float ramp = s.fadeIn > 1e-4 ? min(unitAge / s.fadeIn, 1.0) : 1.0;

    return vec4(clamp(s.tint * cool * p.tintScale, 0.0, 1.0), clamp(alpha * ramp, 0.0, 1.0));
}
