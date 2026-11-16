package dev.lsiwo.motionfg;

import android.hardware.DataSpace;
import android.media.MediaFormat;
import android.view.Display;

final class ColorSignal {
    static final int SDR = 0, PQ = 1, HLG = 2, LINEAR = 3, VIDEO_SDR = 4, SCRGB = 5;
    static final int REC709 = 0, BT2020 = 1, P3 = 2, REC601_525 = 3, REC601_625 = 4;

    static final ColorSignal SRGB = new ColorSignal(SDR, REC709, false);
    static final ColorSignal SDR_VIDEO = new ColorSignal(VIDEO_SDR, REC709, true);

    final int transfer, gamut;
    final boolean limited;

    private ColorSignal(int transfer, int gamut, boolean limited) {
        this.transfer = transfer;
        this.gamut = gamut;
        this.limited = limited;
    }

    boolean hdr() {
        return transfer == PQ || transfer == HLG || transfer == SCRGB;
    }

    String label() {
        return transfer == PQ ? "PQ" : transfer == HLG ? "HLG" : transfer == SCRGB ? "scRGB" : "SDR";
    }

    static String outputLabel(int mode) {
        return mode == GlContext.PQ ? "HDR10 PQ" : mode == GlContext.SCRGB ? "HDR scRGB" : "SDR";
    }
    static boolean displayHdr10(Display display) {
        if (display == null) return false;
        for (int type : display.getHdrCapabilities().getSupportedHdrTypes()) {
            if (type == Display.HdrCapabilities.HDR_TYPE_HDR10
                    || type == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS) return true;
        }
        return false;
    }

    static ColorSignal capture(int space) {
        if (space == DataSpace.DATASPACE_UNKNOWN) return SRGB;
        if (space == DataSpace.DATASPACE_SCRGB_LINEAR) return new ColorSignal(SCRGB, REC709, false);
        int transfer = DataSpace.getTransfer(space);
        int standard = DataSpace.getStandard(space);
        return new ColorSignal(transfer == DataSpace.TRANSFER_ST2084 ? PQ
                : transfer == DataSpace.TRANSFER_HLG ? HLG
                : transfer == DataSpace.TRANSFER_LINEAR ? LINEAR
                : transfer == DataSpace.TRANSFER_SMPTE_170M ? VIDEO_SDR : SDR,
                standard == DataSpace.STANDARD_BT2020 || standard == DataSpace.STANDARD_BT2020_CONSTANT_LUMINANCE
                        ? BT2020 : standard == DataSpace.STANDARD_DCI_P3 ? P3 : REC709,
                DataSpace.getRange(space) == DataSpace.RANGE_LIMITED);
    }

    static ColorSignal video(MediaFormat format) {
        int t = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
        int c = format.getInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
        return new ColorSignal(t == MediaFormat.COLOR_TRANSFER_ST2084 ? PQ
                : t == MediaFormat.COLOR_TRANSFER_HLG ? HLG
                : t == MediaFormat.COLOR_TRANSFER_LINEAR ? LINEAR : VIDEO_SDR,
                c == MediaFormat.COLOR_STANDARD_BT2020 ? BT2020
                        : c == MediaFormat.COLOR_STANDARD_BT601_PAL ? REC601_625
                        : c == MediaFormat.COLOR_STANDARD_BT601_NTSC ? REC601_525 : REC709,
                format.getInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                        == MediaFormat.COLOR_RANGE_LIMITED);
    }
}
