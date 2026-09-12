package io.github.choumax.a1260xiaozhi.wake;

/** Original bounded 16 kHz MFCC extractor. No recognizer, model, native library or network. */
public final class WakeFeatures {
    public static final int SAMPLE_RATE = 16000;
    public static final int MAX_SAMPLES = 48000;
    public static final int MIN_SAMPLES = 4800;
    public static final int DIMENSIONS = 12;
    public static final int MAX_FRAMES = 299;
    private static final int WINDOW = 400, HOP = 160, FFT = 512, MEL = 24;
    private final double[] window = new double[WINDOW];
    private final double[][] filters = new double[MEL][FFT / 2 + 1];
    private final double[][] cosine = new double[DIMENSIONS][MEL];

    public WakeFeatures() {
        for (int i = 0; i < WINDOW; i++) window[i] = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (WINDOW - 1));
        double low = mel(80), high = mel(7600);
        double[] edges = new double[MEL + 2];
        for (int i = 0; i < edges.length; i++) edges[i] = 700 * (Math.exp((low + (high - low) * i / (MEL + 1)) / 1127) - 1);
        for (int band = 0; band < MEL; band++) {
            for (int bin = 0; bin <= FFT / 2; bin++) {
                double hz = bin * (double) SAMPLE_RATE / FFT;
                filters[band][bin] = Math.max(0, Math.min((hz - edges[band]) / (edges[band + 1] - edges[band]),
                        (edges[band + 2] - hz) / (edges[band + 2] - edges[band + 1])));
            }
        }
        for (int c = 0; c < DIMENSIONS; c++) for (int b = 0; b < MEL; b++)
            cosine[c][b] = Math.cos(Math.PI * (c + 1) * (b + 0.5) / MEL) * Math.sqrt(2.0 / MEL) / 10;
    }

    public float[][] extract(short[] pcm) {
        if (pcm == null || pcm.length < MIN_SAMPLES || pcm.length > MAX_SAMPLES)
            throw new IllegalArgumentException("Wake segment must contain 0.3 to 3 seconds of PCM");
        if (rms(pcm, pcm.length) < 30) throw new IllegalArgumentException("Wake segment is silent");
        int frames = 1 + (pcm.length - WINDOW) / HOP;
        float[][] result = new float[frames][DIMENSIONS];
        double[] real = new double[FFT], imaginary = new double[FFT], power = new double[FFT / 2 + 1], logs = new double[MEL];
        for (int f = 0; f < frames; f++) {
            java.util.Arrays.fill(real, 0); java.util.Arrays.fill(imaginary, 0);
            int offset = f * HOP;
            for (int i = 0; i < WINDOW; i++) {
                int at = offset + i;
                double previous = at == 0 ? 0 : pcm[at - 1];
                real[i] = (pcm[at] - 0.97 * previous) / 32768.0 * window[i];
            }
            fft(real, imaginary);
            for (int b = 0; b < power.length; b++) power[b] = real[b] * real[b] + imaginary[b] * imaginary[b];
            for (int m = 0; m < MEL; m++) {
                double energy = 0;
                for (int b = 0; b < power.length; b++) energy += power[b] * filters[m][b];
                logs[m] = Math.log(Math.max(energy, 1e-12));
            }
            // c0 is omitted: the retained coefficients do not encode overall recording volume.
            for (int c = 0; c < DIMENSIONS; c++) {
                double sum = 0;
                for (int m = 0; m < MEL; m++) sum += logs[m] * cosine[c][m];
                result[f][c] = (float) sum;
            }
        }
        return result;
    }

    public static double rms(short[] pcm, int length) {
        if (pcm == null || length <= 0 || length > pcm.length) return 0;
        double sum = 0;
        for (int i = 0; i < length; i++) sum += (double) pcm[i] * pcm[i];
        return Math.sqrt(sum / length);
    }

    private static double mel(double hz) { return 1127 * Math.log(1 + hz / 700); }

    private static void fft(double[] re, double[] im) {
        for (int i = 1, j = 0; i < FFT; i++) {
            int bit = FFT >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; }
        }
        for (int size = 2; size <= FFT; size <<= 1) {
            double angle = -2 * Math.PI / size, wrStep = Math.cos(angle), wiStep = Math.sin(angle);
            for (int start = 0; start < FFT; start += size) {
                double wr = 1, wi = 0;
                for (int j = 0; j < size / 2; j++) {
                    int a = start + j, b = a + size / 2;
                    double r = re[b] * wr - im[b] * wi, v = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - r; im[b] = im[a] - v; re[a] += r; im[a] += v;
                    double next = wr * wrStep - wi * wiStep;
                    wi = wr * wiStep + wi * wrStep; wr = next;
                }
            }
        }
    }
}
