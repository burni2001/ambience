package tv.ambience.sync.core;

/**
 * Ordnet jeder Lampe die LEDs in ihrer Nähe zu. Die LEDs liegen auf dem Bildrand
 * (x: -1 links .. 1 rechts, z: -1 unten .. 1 oben), die Lampe an ihrer Stelle aus dem
 * Entertainment-Bereich. Je näher eine LED, desto mehr zählt ihre Farbe.
 */
public final class Mapper {
    private final double[][] weight; // [Lampe][LED]
    private final int leds;

    /** topo: LEDs links, oben, rechts, unten. spread: Einzugsbereich (in denselben Einheiten wie x und z). */
    public Mapper(int[] topo, double[] lampX, double[] lampZ, double spread) {
        leds = topo[0] + topo[1] + topo[2] + topo[3];
        double[] lx = new double[leds];
        double[] lz = new double[leds];
        double[] len = new double[leds]; // Stück des Bildrands, für das die LED steht
        int p = 0;
        // Reihenfolge der API: im Uhrzeigersinn von vorn gesehen, beginnend links unten.
        for (int k = 0; k < topo[0]; k++, p++) { // links, von unten nach oben
            lx[p] = -1;
            lz[p] = -1 + 2 * (k + 0.5) / topo[0];
            len[p] = 2.0 / topo[0];
        }
        for (int k = 0; k < topo[1]; k++, p++) { // oben, von links nach rechts
            lx[p] = -1 + 2 * (k + 0.5) / topo[1];
            lz[p] = 1;
            len[p] = 2.0 / topo[1];
        }
        for (int k = 0; k < topo[2]; k++, p++) { // rechts, von oben nach unten
            lx[p] = 1;
            lz[p] = 1 - 2 * (k + 0.5) / topo[2];
            len[p] = 2.0 / topo[2];
        }
        for (int k = 0; k < topo[3]; k++, p++) { // unten, von rechts nach links
            lx[p] = 1 - 2 * (k + 0.5) / topo[3];
            lz[p] = -1;
            len[p] = 2.0 / topo[3];
        }
        weight = new double[lampX.length][leds];
        for (int c = 0; c < lampX.length; c++) {
            double x = Math.max(-1, Math.min(1, lampX[c]));
            double z = Math.max(-1, Math.min(1, lampZ[c]));
            double sum = 0;
            for (int l = 0; l < leds; l++) {
                double d2 = (x - lx[l]) * (x - lx[l]) + (z - lz[l]) * (z - lz[l]);
                // Mal Randstück, sonst zählte die dichter bestückte Oberkante mehrfach.
                weight[c][l] = len[l] * Math.exp(-d2 / (2 * spread * spread));
                sum += weight[c][l];
            }
            for (int l = 0; l < leds; l++) weight[c][l] /= sum;
        }
    }

    /** in: r,g,b je LED. out: r,g,b je Lampe, im selben Wertebereich. */
    public void map(int[] in, double[] out) {
        for (int c = 0; c < weight.length; c++) {
            double r = 0;
            double g = 0;
            double b = 0;
            for (int l = 0; l < leds; l++) {
                double w = weight[c][l];
                r += w * in[3 * l];
                g += w * in[3 * l + 1];
                b += w * in[3 * l + 2];
            }
            out[3 * c] = r;
            out[3 * c + 1] = g;
            out[3 * c + 2] = b;
        }
    }

    /** Anteil (0..1), den die LEDs einer Seite (0 links, 1 oben, 2 rechts, 3 unten) an Lampe c haben. */
    public double share(int c, int[] topo, int side) {
        int start = 0;
        for (int s = 0; s < side; s++) start += topo[s];
        double sum = 0;
        for (int l = start; l < start + topo[side]; l++) sum += weight[c][l];
        return sum;
    }
}
