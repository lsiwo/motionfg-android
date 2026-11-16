package dev.lsiwo.motionfg;

final class CaptureGeometry {
    private static final double PIXEL_BUDGET = 8_294_400.0;
    final int bufferWidth, bufferHeight, width, height;
    final double aspect;
    final float cropX, cropY;

    private CaptureGeometry(int bufferWidth, int bufferHeight, double aspect) {
        this.bufferWidth = bufferWidth;
        this.bufferHeight = bufferHeight;
        this.aspect = aspect;
        double contentWidth = Math.min(bufferWidth, bufferHeight * aspect);
        double contentHeight = Math.min(bufferHeight, bufferWidth / aspect);
        cropX = (float) (contentWidth / bufferWidth);
        cropY = (float) (contentHeight / bufferHeight);
        width = Math.max(2, (int) Math.round(contentWidth));
        height = Math.max(2, (int) Math.round(contentHeight));
    }

    static CaptureGeometry source(int contentWidth, int contentHeight, int percent,
            int fixedWidth, int fixedHeight, int maxTexture) {
        double sourceWidth = Math.max(2, contentWidth), sourceHeight = Math.max(2, contentHeight);
        boolean custom = fixedWidth > 0 && fixedHeight > 0;
        double w = custom ? fixedWidth : sourceWidth * percent / 100.0;
        double h = custom ? fixedHeight : sourceHeight * percent / 100.0;
        double scale = Math.min(1.0, Math.max(2, maxTexture) / Math.max(w, h));
        scale = Math.min(scale, Math.sqrt(PIXEL_BUDGET / (w * h)));
        return new CaptureGeometry(dimension(w, scale), dimension(h, scale), sourceWidth / sourceHeight);
    }

    boolean matches(CaptureGeometry other) {
        return other != null && bufferWidth == other.bufferWidth && bufferHeight == other.bufferHeight
                && aspect == other.aspect;
    }

    void cropTransform(float[] transform) {
        if (cropX == 1f && cropY == 1f) return;
        float x = 0.5f * (1f - cropX), y = 0.5f * (1f - cropY);
        for (int row = 0; row < 4; row++) {
            transform[12 + row] += transform[row] * x + transform[4 + row] * y;
            transform[row] *= cropX;
            transform[4 + row] *= cropY;
        }
    }

    private static int dimension(double size, double scale) {
        return Math.max(2, scale == 1.0 ? (int) Math.round(size) : (int) (size * scale));
    }
}
