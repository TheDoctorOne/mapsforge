/*
 * Copyright 2017 usrusr
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.mapsforge.map.layer.hills;

import org.mapsforge.core.util.IOUtils;
import org.mapsforge.core.util.MercatorProjection;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * simulates diffuse lighting (without self-shadowing) except for scaling the light values below horizontal and above horizontal
 * differently so that both make full use of the available dynamic range while maintinging horizontal neutral identical to {@link SimpleShadingAlgorithm}
 * and to the standard neutral value that is filled in when there is no hillshading but the always-option is set to true in the theme.
 * <p>
 * <p>More accurate than {@link SimpleShadingAlgorithm}, but maybe not as useful for visualizing both softly rolling hills and dramatic mountain ranges at the same time.</p>
 */
public class DiffuseLightShadingAlgorithm implements ShadingAlgorithm {

    private static final Logger LOGGER = Logger.getLogger(DiffuseLightShadingAlgorithm.class.getName());

    /**
     * light height (relative to 1:1:x)
     */
    private double a;

    private final double ast2;
    private final double neutral;

    public double getLightHeight() {
        return a;
    }

    public DiffuseLightShadingAlgorithm() {
        this(50f);
    }

    /**
     * height angle of light source over ground (in degrees 0..90)
     */
    public DiffuseLightShadingAlgorithm(float heightAngle) {

        this.a = heightAngleToRelativeHeight(heightAngle);
        ast2 = Math.sqrt(2 + this.a * this.a);
        neutral = calculateRaw(0, 0);
    }

    static double heightAngleToRelativeHeight(float heightAngle) {
        double radians = heightAngle / 180d * Math.PI;

        return Math.tan(radians) * Math.sqrt(2d);
    }

    @Override
    public int getAxisLenght(HgtCache.HgtFileInfo source) {
        long size = source.getSize();
        long elements = size / 2;
        int rowLen = (int) Math.ceil(Math.sqrt(elements));
        if (rowLen * rowLen * 2 != size) {
            return 0;
        }
        return rowLen - 1;
    }

    @Override
    public RawShadingResult transformToByteBuffer(HgtCache.HgtFileInfo source, int padding) {
        int axisLength = getAxisLenght(source);
        int rowLen = axisLength + 1;
        BufferedInputStream in = null;
        try {
            in = source.openInputStream();


            byte[] bytes = convert(in, axisLength, rowLen, padding, source);
            return new RawShadingResult(bytes, axisLength, axisLength, padding);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            return null;
        } finally {
            IOUtils.closeQuietly(in);
        }
    }

    private byte[] convert(InputStream in, int axisLength, int rowLen, int padding, HgtCache.HgtFileInfo fileInfo) throws IOException {
        byte[] bytes;

        short[] ringbuffer = new short[rowLen];
        bytes = new byte[(axisLength + 2 * padding) * (axisLength + 2 * padding)];

        DataInputStream din = new DataInputStream(in);

        int outidx = (axisLength + 2 * padding) * padding + padding;
        int rbcur = 0;
        {
            short last = 0;
            for (int col = 0; col < rowLen; col++) {
                last = readNext(din, last);
                ringbuffer[rbcur++] = last;
            }
        }

        double southPerPixel = MercatorProjection.calculateGroundResolution(fileInfo.southLat(), axisLength * 170);
        double northPerPixel = MercatorProjection.calculateGroundResolution(fileInfo.northLat(), axisLength * 170);

        double southPerPixelByLine = southPerPixel / (2 * axisLength);
        double northPerPixelByLine = northPerPixel / (2 * axisLength);

        for (int line = 1; line <= axisLength; line++) {
            if (rbcur >= rowLen) {
                rbcur = 0;
            }
            short nw = ringbuffer[rbcur];
            short sw = readNext(din, nw);
            ringbuffer[rbcur++] = sw;
            double halfmetersPerPixel = (southPerPixelByLine * line + northPerPixelByLine * (axisLength - line));
            for (int col = 1; col <= axisLength; col++) {
                short ne = ringbuffer[rbcur];
                short se = readNext(din, ne);
                ringbuffer[rbcur++] = se;

                int noso = -((se - ne) + (sw - nw));

                int eawe = -((ne - nw) + (se - sw));

                int zeroIsFlat = calculate(noso / halfmetersPerPixel, eawe / halfmetersPerPixel);

                int intVal = Math.min(255, Math.max(0, zeroIsFlat + 127));

                int shade = intVal & 0xFF;

                bytes[outidx++] = (byte) shade;

                nw = ne;
                sw = se;
            }
            outidx += 2 * padding;
        }
        return bytes;
    }


    private static final double halfPi = Math.PI / 2d;


    int calculate(double n, double e) {
        double raw = calculateRaw(n, e);

        double v = raw - neutral;

        if (v < 0) {
            return (int) Math.round((128 * (v / neutral)));
        } else if (v > 0) {
            return (int) Math.round((127 * (v / (1d - neutral))));
        } else {
            return 0;
        }
    }

    /**
     * return 0..1
     */
    double calculateRaw(double n, double e) {
        // calculate the distance of the normal vector to a plane orthogonal to the light source and passing through zero,
        // the fraction of distance to vector lenght is proportional to the amount of light that would be hitting a disc
        // orthogonal to the normal vector
        double normPlaneDist = (e + n + a) / (ast2 * Math.sqrt(n * n + e * e + 1));

        double lightness = Math.max(0, normPlaneDist);
        return lightness;
    }

    private static short readNext(DataInputStream din, short fallback) throws IOException {
        short read = din.readShort();
        if (read == Short.MIN_VALUE)
            return fallback;
        return read;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DiffuseLightShadingAlgorithm that = (DiffuseLightShadingAlgorithm) o;

        return Double.compare(that.a, a) == 0;
    }

    @Override
    public int hashCode() {
        long temp = Double.doubleToLongBits(a);
        return (int) (temp ^ (temp >>> 32));
    }
}