package com.tvkouame.studio;

import java.util.Arrays;

/**
 * Styles de musique : chacun règle le tempo, la suite d'accords, la batterie, la basse, le piano et le solo.
 * Les motifs sont écrits sur 16 pas (une mesure à 4 temps) : x = coup, chiffre = degré (basse 1-3, solo 1-6).
 */
public final class Styles {

    private Styles() {}

    // nom, description, grosse caisse, caisse claire, charley, charley ouvert, clap, basse, piano, solo
    private static final String[][] DATA = {
            {"Reggae", "76 bpm · coup sur le 3e temps, piano à contretemps",
                    "........x.......", "........x.......", "x.x.x.x.x.x.x.x.", "..............x.", "................",
                    "1.....1.2.....3.", "..x...x...x...x.", "3.....2.5...3..."},
            {"Jazz swing", "120 bpm · ride, basse qui marche, accords ii-V-I",
                    "x.........x.....", "......x.......x.", "x...x.x.x...x.x.", "................", "................",
                    "1...2...3...2...", "x.....x...x.....", "..3.4.5...6.5.3."},
            {"Adoration", "68 bpm · lent, doux, piano en arpèges",
                    "x.......x.......", "............x...", "x...x...x...x...", "................", "................",
                    "1.......1.......", "x.x.x.x.x.x.x.x.", "3.......5......."},
            {"Cantique", "84 bpm · posé, accords plaqués, comme à l'église",
                    "x.......x.......", "....x.......x...", "x...x...x...x...", "................", "................",
                    "1...1...2...1...", "x...x...x...x...", "3...2...1...3..."},
            {"Gospel", "96 bpm · entraînant, claps sur 2 et 4",
                    "x.....x.x.......", "....x.......x...", "x.x.x.x.x.x.x.x.", "..............x.", "....x.......x...",
                    "1..1..1.3.1.2...", "x..x..x...x.x...", "3..4.5.3....6.5."},
            {"Salsa", "100 bpm · clave, tumbao de basse, montuno au piano",
                    "x.......x.......", "....x.....x.x...", "x.x.x.x.x.x.x.x.", "..............x.", "x.....x.....x...",
                    "1.....1.....2...", "x..x..x...x.x...", "3.2.1...3.5.4..."},
            {"Rumba congolaise", "110 bpm · guitare-piano qui roule, basse chantante",
                    "x.......x.......", "....x.......x...", "x.x.x.x.x.x.x.x.", "................", "................",
                    "1..1..2..1..1.3.", "x.x.x.x.x.x.x.x.", "3.4.5...3.2...1."},
            {"Afrobeat", "100 bpm · groove syncopé, claps sur le 4e temps",
                    "x.....x...x.....", "....x.......x...", "x.x.x.x.x.x.x.x.", "......x.......x.", "............x...",
                    "1..1..1...2.3...", "x..x..x...x...x.", "2.3.....5.3....."},
            {"Zouk", "96 bpm · doux et dansant",
                    "x.....x.x.......", "............x...", "x.x.x.x.x.x.x.x.", "................", "................",
                    "1.....2.1.....3.", "x.x.x.x.x.x.x.x.", "3.....5.3.2....."},
            {"Coupé-décalé", "128 bpm · quatre temps, claps, énergique",
                    "x...x...x...x...", "................", "................", "..x...x...x...x.", "....x.......x...",
                    "..1...1...1...1.", "..x...x...x...x.", "3.5.3.5.6.5.3.5."},
            {"Blues", "90 bpm · basse qui monte, accords posés",
                    "x.......x.......", "....x.......x...", "x.....x.x.....x.", "................", "................",
                    "1...2...3...2...", "x.....x.x.....x.", "3.4.5...3.....1."},
            {"Bossa nova", "120 bpm · batterie légère, accords syncopés",
                    "x.....x.x.....x.", "..x..x...x..x...", "x.x.x.x.x.x.x.x.", "................", "................",
                    "1.....2.1.....2.", "..x..x...x..x...", "3...4.5.....3..."},
            {"Pop", "110 bpm · simple et clair",
                    "x.......x.x.....", "....x.......x...", "x.x.x.x.x.x.x.x.", "..............x.", "................",
                    "1..1..1.3.1.2...", "x.....x...x.....", "3...2.1.3...5..."},
            {"Hip-hop", "90 bpm · lourd, mineur",
                    "x.....x...x.....", "....x.......x...", "x.x.x.x.x.x.x.x.", "..............x.", "................",
                    "1.....1...1.....", "x.......x.......", "3.....5.3.2....."}
    };

    // tempo, suite d'accords (voir BeatModel.PROG_NAMES), style de piano (voir BeatModel.PIANO_STYLES)
    private static final int[][] CFG = {
            {76, 2, 0}, {120, 4, 0}, {68, 0, 1}, {84, 2, 0}, {96, 3, 0}, {100, 4, 3}, {110, 1, 1},
            {100, 1, 0}, {96, 0, 1}, {128, 5, 0}, {90, 2, 0}, {120, 4, 0}, {110, 0, 0}, {90, 5, 0}
    };

    public static final int COUNT = DATA.length;

    public static String name(int i) { return DATA[i][0]; }

    public static String info(int i) { return DATA[i][1]; }

    public static String[] menu() {
        String[] l = new String[COUNT];
        for (int i = 0; i < COUNT; i++) l[i] = DATA[i][0] + "  ·  " + DATA[i][1];
        return l;
    }

    private static void bools(boolean[] dst, String p) {
        Arrays.fill(dst, false);
        for (int i = 0; i < p.length() && i < dst.length; i++) dst[i] = p.charAt(i) == 'x';
    }

    private static void ints(int[] dst, String p, int max) {
        Arrays.fill(dst, 0);
        for (int i = 0; i < p.length() && i < dst.length; i++) {
            char c = p.charAt(i);
            if (c >= '1' && c <= '6') dst[i] = Math.min(max, c - '0');
        }
    }

    /** Règle les motifs, le tempo et les accords dans ce style. La tonalité, les notes libres et qui joue dans quelle mesure restent. */
    public static void apply(BeatModel m, int idx) {
        idx = Math.max(0, Math.min(COUNT - 1, idx));
        String[] d = DATA[idx];
        int[] c = CFG[idx];
        m.style = idx;
        m.bpm = c[0];
        m.beatsPerBar = 4;
        m.applyProgression(m.key, c[1]);
        m.pianoStyle = c[2];
        for (int r = 0; r < 5; r++) bools(m.drum[r], d[2 + r]);
        ints(m.bassStep, d[7], 3);
        bools(m.pianoHit, d[8]);
        ints(m.soloStep, d[9], 6);
    }
}
