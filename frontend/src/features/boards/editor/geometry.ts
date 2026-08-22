import { z } from "zod"

export const normalizedBoundsSchema = z.strictObject({
  height: z.number().positive().max(1),
  width: z.number().positive().max(1),
  x: z.number().min(0).max(1),
  y: z.number().min(0).max(1),
}).refine(({ height, width, x, y }) => x + width <= 1 && y + height <= 1)

export type Bounds = z.infer<typeof normalizedBoundsSchema>
type Size = { readonly height: number; readonly width: number }
type PixelBounds = Bounds

export function normalizedFromPixels(bounds: PixelBounds, canvas: Size): Bounds {
  return normalizedBoundsSchema.parse({
    height: bounds.height / canvas.height,
    width: bounds.width / canvas.width,
    x: bounds.x / canvas.width,
    y: bounds.y / canvas.height,
  })
}

export function collides(left: Bounds, right: Bounds): boolean {
  return left.x < right.x + right.width && left.x + left.width > right.x
    && left.y < right.y + right.height && left.y + left.height > right.y
}

export function clampBounds(bounds: Bounds): Bounds {
  const width = Math.min(1, Math.max(0.08, bounds.width))
  const height = Math.min(1, Math.max(0.08, bounds.height))
  return normalizedBoundsSchema.parse({
    height,
    width,
    x: Math.max(0, Math.min(1 - width, bounds.x)),
    y: Math.max(0, Math.min(1 - height, bounds.y)),
  })
}

export function defaultPlacement(index: number): Bounds {
  const width = 0.24
  const height = 0.18
  const column = index % 3
  const row = Math.floor(index / 3) % 4
  return normalizedBoundsSchema.parse({ height, width, x: column * width, y: row * height })
}
