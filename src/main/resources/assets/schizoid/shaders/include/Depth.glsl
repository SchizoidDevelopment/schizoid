// Minecraft 26.2 renders with a reversed-Z depth buffer: 1 is the near plane and 0 is the far plane.
// The classic OpenGL formula (2nf / (f + n - d(f - n))) is inverted for that buffer and returned ~0 for
// every pixel, so anything masking by distance - the blur module's fog, for one - was permanently closed.
//
// Measured, not assumed (spatial depth probe, 1936x1056, near=0.05 far=1024):
//   ground a couple of metres away  raw depth 0.0199 -> 2.5 m here, 0.1 m with the classic formula
//   sky / horizon                  raw depth 0.0000 -> 1024 m here, 0.1 m with the classic formula
#define linearizeDepth(depth, near, far) (near * far) / (near + depth * (far - near))
