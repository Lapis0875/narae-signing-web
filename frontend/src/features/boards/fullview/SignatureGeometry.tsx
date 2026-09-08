import { memo, useCallback, useEffect, useRef } from "react"
import { calculatePreviewLineWidth, drawStroke } from "../../signing/pad/canvasRenderer.ts"
import type { SignaturePayload } from "../../signing/pad/signaturePayload.ts"

type SignatureGeometryProps = { readonly signature: SignaturePayload }

export const SignatureGeometry = memo(function SignatureGeometry({ signature }: SignatureGeometryProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const redraw = useCallback(() => {
    const canvas = canvasRef.current
    const context = canvas?.getContext("2d")
    if (canvas === null || canvas === undefined || context === null || context === undefined) return
    const width = canvas.clientWidth
    const height = canvas.clientHeight
    const density = Math.max(1, window.devicePixelRatio)
    canvas.width = Math.round(width * density)
    canvas.height = Math.round(height * density)
    context.setTransform(density, 0, 0, density, 0, 0)
    context.clearRect(0, 0, width, height)
    context.strokeStyle = "#000000"
    context.lineCap = "round"
    context.lineJoin = "round"
    context.lineWidth = calculatePreviewLineWidth(width, height)
    for (const stroke of signature.strokes) drawStroke({ context, height, width }, stroke)
  }, [signature])

  useEffect(() => {
    const canvas = canvasRef.current
    if (canvas === null) return
    const observer = new ResizeObserver(redraw)
    observer.observe(canvas)
    redraw()
    return () => observer.disconnect()
  }, [redraw])

  return <canvas aria-label="제출된 서명" className="full-view-signature" data-testid="submitted-signature" ref={canvasRef} role="img" />
})
