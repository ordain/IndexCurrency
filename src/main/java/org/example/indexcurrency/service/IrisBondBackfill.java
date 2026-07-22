package org.example.indexcurrency.service;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

/**
 * Iris Bond - faktorregression och bakatsimulering (Java-port).
 * ============================================================
 *
 * Skattar Captor Iris Bonds veckoavkastning mot rante-/spreadfaktorer och
 * extrapolerar bakat till innan fonden fanns (start ~nov 2017).
 *
 * Ekonomisk logik (samma som Python-versionen):
 *   Fonden ~ kort/medellang kassaportfolj av svenska sakerstallda/IG-obligationer,
 *   vars duration forlangs till 11 år via receiver-swappar.
 *   s = swapranta (~10y), c = covered-spread (covered yield - swap).
 *     r_t ~ -(D_overlay+D_cash)*ds_t  -  D_cash*dc_t  +  (s+c)_{t-1}/52  +  0.5*conv*ds^2
 *   => ds-koefficienten  = fondens TOTALA modifierade duration (~ -9..-10)
 *      dc-koefficienten  = enbart kassabenets duration (~ -1..-4)
 *      carry-koefficienten ~ 1 om ratt specificerad.
 *
 * KRITISKT for backfill: carry ligger som NIVATERM (s+c)/52, inte som intercept,
 * sa att carryn skalar med den historiska rantenivan nar du extrapolerar bakat.
 *
 * Bygg & kor:  javac IrisBondBackfill.java && java IrisBondBackfill
 *
 * Lagen:
 *   MODE = "demo" -> syntetisk data med kand "sanning", verifierar mekaniken
 *   MODE = "real" -> laser CSV-filer (date,value per rad; ranta i procent)
 *
 * I real-lage byggs faktorerna fran Riksbank-serier (hamta med RiksbankFetcher):
 *   ranta (s)      = 10y statsobligation
 *   covered-spread = 5y bostadsobligation - 5y statsobligation
 * och den bakatsimulerade veckoserien skrivs till iris_backfill_weekly.csv.
 */
public class IrisBondBackfill {

    // ---------------------------------------------------------------- //
    //  KONFIG
    // ---------------------------------------------------------------- //
    static final String MODE       = System.getProperty("mode", "real"); // "demo"/"real" (-Dmode=demo)
    static final double FEE_ANNUAL = 0.0030;     // ca-avgift (fangas av interceptet)
    static final double TARGET_DUR = 11.0;       // realiserad mod. duration ~11 (mandat 10-15y); sanity-check
    static final int    HAC_LAGS   = 4;          // Newey-West Bartlett-lags

    // Dela upp rantan i 5y- och 10y-nyckelrater (summerar key-rate-durationer -> mindre attenuering)?
    // Styr vid korning: -Dkeyrate=false ger enfaktormodellen (bara 10y). Default: pa.
    static final boolean KEY_RATE  = Boolean.parseBoolean(System.getProperty("keyrate", "true"));

    // real-lage: faktorerna byggs fran Riksbank-serier (procent). Hamta med RiksbankFetcher.
    //   ranta (duration-overlay) = 10y statsobligation
    //   covered-spread           = 5y bostadsobligation - 5y statsobligation
    static final String PATH_NAV     = "captor_iris_bond_nav.csv"; // ackumulerande NAV
    static final String PATH_G10     = "stats_10y.csv";            // 10y statsobligation, PROCENT
    static final String PATH_G5      = "stats_5y.csv";             // 5y statsobligation, PROCENT
    static final String PATH_B5      = "bostad_5y.csv";            // 5y bostadsobligation, PROCENT
    static final String OUT_BACKFILL = "iris_backfill_weekly.csv"; // bakatsimulerad veckoserie (real)

    // Daglig proxyserie i appens cache-format. Lagg CAPTORIRISBF.csv i cache/ och peka
    // SE0012204758/SE0012204766 mot CAPTORIRISBF i app-defaults.properties, sa splitsar appen den
    // sjalv (spliceProxy ankrar mot fondens forsta NAV).
    static final String PROXY_SYMBOL = "CAPTORIRISBF";
    static final String OUT_PROXY    = "CAPTORIRISBF.csv";

    // ================================================================ //
    //  1. DATA: serier som TreeMap<datum, varde>
    // ================================================================ //
    static TreeMap<LocalDate, Double> readCsv(String path) throws IOException {
        TreeMap<LocalDate, Double> s = new TreeMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] p = line.split(",");
                if (first) {                       // hoppa ev. rubrikrad
                    first = false;
                    try { LocalDate.parse(p[0].trim()); } catch (Exception e) { continue; }
                }
                s.put(LocalDate.parse(p[0].trim()), Double.parseDouble(p[1].trim()));
            }
        }
        return s;
    }

    /** Veckosampling anchored pa fredag (motsv. pandas resample("W-FRI").last()). */
    static LocalDate weekEndingFriday(LocalDate d) {
        int daysToFri = (DayOfWeek.FRIDAY.getValue() - d.getDayOfWeek().getValue() + 7) % 7;
        return d.plusDays(daysToFri);
    }

    static TreeMap<LocalDate, Double> toWeekly(TreeMap<LocalDate, Double> daily) {
        TreeMap<LocalDate, Double> w = new TreeMap<>();
        for (var e : daily.entrySet())            // sorterad -> sista i veckan vinner
            w.put(weekEndingFriday(e.getKey()), e.getValue());
        return w;
    }

    /** Punktvis b - g pa gemensamma datum (t.ex. covered-spread = bostad - stat). */
    static TreeMap<LocalDate, Double> minus(TreeMap<LocalDate, Double> b, TreeMap<LocalDate, Double> g) {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        for (var e : b.entrySet()) {
            Double gv = g.get(e.getKey());
            if (gv != null) out.put(e.getKey(), e.getValue() - gv);
        }
        return out;
    }

    // ================================================================ //
    //  2. BYGG VECKOPANEL  (kolumner: r, dswap, dspr, carry, conv)
    // ================================================================ //
    static class Panel {
        List<LocalDate> dates = new ArrayList<>();
        double[] r, dswap, dg5, dspr, carry, conv;   // dswap = d(10y), dg5 = d(5y)
    }

    static Panel buildWeekly(TreeMap<LocalDate, Double> nav,
                             TreeMap<LocalDate, Double> g10,
                             TreeMap<LocalDate, Double> g5,
                             TreeMap<LocalDate, Double> spread) {
        TreeMap<LocalDate, Double> navW = toWeekly(nav);
        TreeMap<LocalDate, Double> g10W = toWeekly(g10);
        TreeMap<LocalDate, Double> g5W  = toWeekly(g5);
        TreeMap<LocalDate, Double> spW  = toWeekly(spread);

        // inner join pa gemensamma veckodatum
        List<LocalDate> keys = new ArrayList<>();
        for (LocalDate d : navW.keySet())
            if (g10W.containsKey(d) && g5W.containsKey(d) && spW.containsKey(d)) keys.add(d);
        java.util.Collections.sort(keys);

        int n = keys.size();
        double[] navv = new double[n], g10v = new double[n], g5v = new double[n], spv = new double[n];
        for (int i = 0; i < n; i++) {
            navv[i] = navW.get(keys.get(i));
            g10v[i] = g10W.get(keys.get(i));
            g5v[i]  = g5W.get(keys.get(i));
            spv[i]  = spW.get(keys.get(i));
        }

        // forsta raden tappas (diff/pct_change kraver t-1)
        Panel p = new Panel();
        int m = n - 1;
        p.r = new double[m]; p.dswap = new double[m]; p.dg5 = new double[m]; p.dspr = new double[m];
        p.carry = new double[m]; p.conv = new double[m];
        for (int i = 1; i < n; i++) {
            int j = i - 1;
            p.dates.add(keys.get(i));
            p.r[j]     = navv[i] / navv[i - 1] - 1.0;
            p.dswap[j] = (g10v[i] - g10v[i - 1]) / 100.0;
            p.dg5[j]   = (g5v[i]  - g5v[i - 1])  / 100.0;
            p.dspr[j]  = (spv[i]  - spv[i - 1])  / 100.0;
            p.carry[j] = (g10v[i - 1] + spv[i - 1]) / 100.0 / 52.0;   // nivaterm, t-1
            p.conv[j]  = 0.5 * p.dswap[j] * p.dswap[j];
        }
        return p;
    }

    // ================================================================ //
    //  3. LINJAR ALGEBRA (allt sma p<=5, sa enkla rutiner racker)
    // ================================================================ //
    static double[][] transpose(double[][] a) {
        double[][] t = new double[a[0].length][a.length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[0].length; j++) t[j][i] = a[i][j];
        return t;
    }

    static double[][] matMul(double[][] a, double[][] b) {
        int n = a.length, m = b[0].length, k = b.length;
        double[][] c = new double[n][m];
        for (int i = 0; i < n; i++)
            for (int l = 0; l < k; l++) {
                double av = a[i][l];
                if (av == 0) continue;
                for (int j = 0; j < m; j++) c[i][j] += av * b[l][j];
            }
        return c;
    }

    static double[] matVec(double[][] a, double[] v) {
        double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            double s = 0;
            for (int j = 0; j < v.length; j++) s += a[i][j] * v[j];
            r[i] = s;
        }
        return r;
    }

    /** Invers via Gauss-Jordan med partiell pivotering. */
    static double[][] inverse(double[][] a) {
        int n = a.length;
        double[][] m = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n + i] = 1.0;
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++)
                if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r;
            double[] tmp = m[col]; m[col] = m[piv]; m[piv] = tmp;
            double d = m[col][col];
            if (Math.abs(d) < 1e-14) throw new ArithmeticException("Singular X'X");
            for (int j = 0; j < 2 * n; j++) m[col][j] /= d;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double f = m[r][col];
                if (f == 0) continue;
                for (int j = 0; j < 2 * n; j++) m[r][j] -= f * m[col][j];
            }
        }
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(m[i], n, inv[i], 0, n);
        return inv;
    }

    // ================================================================ //
    //  4. OLS + NEWEY-WEST (HAC)
    // ================================================================ //
    static class Fit {
        String[] names;
        double[] beta, se, t;
        double r2;
        double[] resid;
    }

    static Fit ols(double[] y, double[][] X, String[] names, int hacLags) {
        int n = X.length, p = X[0].length;
        double[][] Xt = transpose(X);
        double[][] XtX = matMul(Xt, X);
        double[][] XtXinv = inverse(XtX);
        double[] Xty = matVec(Xt, y);
        double[] beta = matVec(XtXinv, Xty);

        double[] resid = new double[n];
        double ybar = 0; for (double v : y) ybar += v; ybar /= n;
        double sst = 0, ssr = 0;
        for (int i = 0; i < n; i++) {
            double fit = 0;
            for (int j = 0; j < p; j++) fit += X[i][j] * beta[j];
            resid[i] = y[i] - fit;
            ssr += resid[i] * resid[i];
            sst += (y[i] - ybar) * (y[i] - ybar);
        }

        // HAC "meat": Bartlett-viktad summa av u_t u_t' med u_t = x_t * e_t
        double[][] meat = new double[p][p];
        for (int t = 0; t < n; t++)
            addOuter(meat, X[t], resid[t], X[t], resid[t], 1.0);
        for (int l = 1; l <= hacLags; l++) {
            double w = 1.0 - (double) l / (hacLags + 1);      // Bartlett-vikt
            for (int t = l; t < n; t++) {
                addOuter(meat, X[t], resid[t], X[t - l], resid[t - l], w);
                addOuter(meat, X[t - l], resid[t - l], X[t], resid[t], w);
            }
        }
        double[][] V = matMul(matMul(XtXinv, meat), XtXinv);   // sandwich

        Fit f = new Fit();
        f.names = names; f.beta = beta; f.resid = resid;
        f.r2 = 1.0 - ssr / sst;
        f.se = new double[p]; f.t = new double[p];
        for (int j = 0; j < p; j++) {
            f.se[j] = Math.sqrt(Math.max(V[j][j], 0));
            f.t[j]  = beta[j] / f.se[j];
        }
        return f;
    }

    /** meat += scale * (xa*ea)(xb*eb)'  */
    static void addOuter(double[][] meat, double[] xa, double ea,
                         double[] xb, double eb, double scale) {
        int p = xa.length;
        for (int i = 0; i < p; i++) {
            double ui = xa[i] * ea * scale;
            if (ui == 0) continue;
            for (int j = 0; j < p; j++) meat[i][j] += ui * (xb[j] * eb);
        }
    }

    // ================================================================ //
    //  5. LJUNG-BOX(h) med chi2-p-varde
    // ================================================================ //
    static double ljungBoxP(double[] e, int h) {
        int n = e.length;
        double mean = 0; for (double v : e) mean += v; mean /= n;
        double c0 = 0; for (double v : e) c0 += (v - mean) * (v - mean);
        double q = 0;
        for (int k = 1; k <= h; k++) {
            double ck = 0;
            for (int t = k; t < n; t++) ck += (e[t] - mean) * (e[t - k] - mean);
            double rho = ck / c0;
            q += rho * rho / (n - k);
        }
        q *= n * (n + 2);
        return chiSqSurvival(q, h);           // P(chi2_h > q)
    }

    // regularized upper incomplete gamma Q(a,x) -> chi2 survival med a=df/2, x=stat/2
    static double chiSqSurvival(double stat, int df) {
        return gammq(df / 2.0, stat / 2.0);
    }
    static double gammq(double a, double x) {
        if (x < 0 || a <= 0) return Double.NaN;
        if (x == 0) return 1.0;
        if (x < a + 1.0) return 1.0 - gser(a, x);   // serie for lower -> 1-P
        return gcf(a, x);                            // kedjebrak for upper
    }
    static double gammln(double xx) {
        double[] cof = {76.18009172947146, -86.50532032941677, 24.01409824083091,
                -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5};
        double x = xx, y = xx, tmp = x + 5.5;
        tmp -= (x + 0.5) * Math.log(tmp);
        double ser = 1.000000000190015;
        for (int j = 0; j < 6; j++) { y += 1; ser += cof[j] / y; }
        return -tmp + Math.log(2.5066282746310005 * ser / x);
    }
    static double gser(double a, double x) {
        double gln = gammln(a), ap = a, sum = 1.0 / a, del = sum;
        for (int n = 0; n < 200; n++) {
            ap += 1; del *= x / ap; sum += del;
            if (Math.abs(del) < Math.abs(sum) * 1e-12) break;
        }
        return sum * Math.exp(-x + a * Math.log(x) - gln);
    }
    static double gcf(double a, double x) {
        double gln = gammln(a), b = x + 1 - a, c = 1e300, d = 1 / b, h = d;
        for (int i = 1; i <= 200; i++) {
            double an = -i * (i - a);
            b += 2; d = an * d + b; if (Math.abs(d) < 1e-300) d = 1e-300;
            c = b + an / c; if (Math.abs(c) < 1e-300) c = 1e-300;
            d = 1 / d; double del = d * c; h *= del;
            if (Math.abs(del - 1) < 1e-12) break;
        }
        return Math.exp(-x + a * Math.log(x) - gln) * h;
    }

    // ================================================================ //
    //  6. SPEC A / B  +  RAPPORT
    // ================================================================ //
    // KEY_RATE=false: en 10y-rantefaktor (dswap). KEY_RATE=true: tva key-rates (dg5, dg10).
    static String[] namesA() { return KEY_RATE ? new String[]{"const","dg5","dg10","dspr","conv"}
                                               : new String[]{"const","dswap","dspr","conv"}; }
    static String[] namesB() { return KEY_RATE ? new String[]{"const","dg5","dg10","dspr","carry","conv"}
                                               : new String[]{"const","dswap","dspr","carry","conv"}; }

    static double[][] designA(Panel p) {
        int m = p.r.length;
        double[][] X = new double[m][KEY_RATE ? 5 : 4];
        for (int i = 0; i < m; i++) {
            int k = 0; X[i][k++] = 1;
            if (KEY_RATE) X[i][k++] = p.dg5[i];
            X[i][k++] = p.dswap[i]; X[i][k++] = p.dspr[i]; X[i][k] = p.conv[i];
        }
        return X;
    }
    static double[][] designB(Panel p) {
        int m = p.r.length;
        double[][] X = new double[m][KEY_RATE ? 6 : 5];
        for (int i = 0; i < m; i++) {
            int k = 0; X[i][k++] = 1;
            if (KEY_RATE) X[i][k++] = p.dg5[i];
            X[i][k++] = p.dswap[i]; X[i][k++] = p.dspr[i]; X[i][k++] = p.carry[i]; X[i][k] = p.conv[i];
        }
        return X;
    }

    /** Beta for en term via namn (0 om ej med), sa layouten kan variera med KEY_RATE. */
    static double coefOf(Fit f, String name) {
        for (int j = 0; j < f.names.length; j++) if (f.names[j].equals(name)) return f.beta[j];
        return 0.0;
    }
    /** Total modifierad duration = -(summan av rante-key-rate-betorna). */
    static double impliedDuration(Fit f) {
        return KEY_RATE ? -(coefOf(f, "dg5") + coefOf(f, "dg10")) : -coefOf(f, "dswap");
    }
    /** En veckoavkastning fran betorna (interceptScale=1 vecka, 52/252 dag). */
    static double rHat(Fit b, double dg5, double dg10, double dspr, double carry, double conv, double interceptScale) {
        double rate = KEY_RATE ? coefOf(b, "dg5") * dg5 + coefOf(b, "dg10") * dg10 : coefOf(b, "dswap") * dg10;
        return coefOf(b, "const") * interceptScale + rate
                + coefOf(b, "dspr") * dspr + coefOf(b, "carry") * carry + coefOf(b, "conv") * conv;
    }

    static void printFit(Fit f) {
        System.out.printf("%-8s %12s %12s %10s%n", "term", "coef", "std err", "t");
        for (int j = 0; j < f.beta.length; j++)
            System.out.printf("%-8s %12.5f %12.5f %10.2f%n", f.names[j], f.beta[j], f.se[j], f.t[j]);
        System.out.printf("R2                : %.4f%n", f.r2);
    }

    // ================================================================ //
    //  7. BACKFILL: applicera Spec B:s betor pa historiska faktorer
    // ================================================================ //
    static void backfill(Fit b, TreeMap<LocalDate, Double> histG10,
                         TreeMap<LocalDate, Double> histG5,
                         TreeMap<LocalDate, Double> histSpread) {
        TreeMap<LocalDate, Double> sw = toWeekly(histG10), g5w = toWeekly(histG5), sp = toWeekly(histSpread);
        List<LocalDate> keys = new ArrayList<>();
        for (LocalDate d : sw.keySet()) if (g5w.containsKey(d) && sp.containsKey(d)) keys.add(d);
        java.util.Collections.sort(keys);

        double idx = 100.0;
        List<String> pretty = new ArrayList<>();
        List<String> csv = new ArrayList<>();
        csv.add("date,index");
        for (int i = 1; i < keys.size(); i++) {
            double s0 = sw.get(keys.get(i-1)), s1 = sw.get(keys.get(i));
            double f0 = g5w.get(keys.get(i-1)), f1 = g5w.get(keys.get(i));
            double p0 = sp.get(keys.get(i-1)), p1 = sp.get(keys.get(i));
            double dg10 = (s1 - s0) / 100.0, dg5 = (f1 - f0) / 100.0, dspr = (p1 - p0) / 100.0;
            double carry = (s0 + p0) / 100.0 / 52.0, conv = 0.5 * dg10 * dg10;
            double rhat = rHat(b, dg5, dg10, dspr, carry, conv, 1.0);
            idx *= (1 + rhat);
            pretty.add(String.format("%s  r_hat=%+.4f  index=%.4f", keys.get(i), rhat, idx));
            csv.add(keys.get(i) + "," + String.format(java.util.Locale.US, "%.6f", idx));
        }
        System.out.println("\nBackfill (sista 3 veckorna):");
        for (int i = Math.max(0, pretty.size() - 3); i < pretty.size(); i++)
            System.out.println("  " + pretty.get(i));
        // Hela veckoserien (bas 100 vid starten). Nasta steg: ankra mot fondens forsta riktiga
        // NAV och splitsa ihop till en sammanhangande historik.
        try {
            java.nio.file.Files.write(java.nio.file.Path.of(OUT_BACKFILL), csv);
            System.out.printf("Skrev %s (%d veckor, bas=100 vid %s)%n", OUT_BACKFILL, csv.size() - 1, keys.get(0));
        } catch (java.io.IOException e) {
            System.out.println("Kunde inte skriva " + OUT_BACKFILL + ": " + e.getMessage());
        }
    }

    // ================================================================ //
    //  7b. DAGLIG PROXY i appens cache-format (redo att splitsa i appen)
    // ================================================================ //
    // Spec B ar skattad pa VECKOdata, men duration/spread-betorna ar frekvensoberoende (r = -D*dy) och
    // carryn skalar med /252 istallet for /52. Sa vi bygger en DAGLIG r_hat och kedjar till ett index -
    // en daglig serie plockar appens dagliga metod upp utan att veckoglapp blaser upp volatiliteten.
    static void writeAppProxy(Fit b, TreeMap<LocalDate, Double> g10,
                              TreeMap<LocalDate, Double> g5, TreeMap<LocalDate, Double> spread) {
        List<LocalDate> keys = new ArrayList<>();
        for (LocalDate d : g10.keySet()) if (g5.containsKey(d) && spread.containsKey(d)) keys.add(d);
        java.util.Collections.sort(keys);
        if (keys.size() < 2) { System.out.println("Proxy: for lite faktordata"); return; }

        double idx = 100.0;
        List<String> rows = new ArrayList<>();
        rows.add("# symbol=" + PROXY_SYMBOL);
        rows.add("# currency=SEK");
        rows.add("# shortName=Captor Iris Bond (backfill)");
        rows.add("# exchangeTimezoneName=Europe/Stockholm");
        rows.add("# fetchedRange=40y");
        rows.add("# source=synthetic");
        rows.add("date,open,high,low,close,adjclose,volume,dividend");
        rows.add(proxyRow(keys.get(0), idx));
        for (int i = 1; i < keys.size(); i++) {
            double s0 = g10.get(keys.get(i-1)), s1 = g10.get(keys.get(i));
            double f0 = g5.get(keys.get(i-1)), f1 = g5.get(keys.get(i));
            double p0 = spread.get(keys.get(i-1)), p1 = spread.get(keys.get(i));
            double dg10 = (s1 - s0) / 100.0, dg5 = (f1 - f0) / 100.0, dspr = (p1 - p0) / 100.0;
            double carry = (s0 + p0) / 100.0 / 252.0, conv = 0.5 * dg10 * dg10;
            double rhat = rHat(b, dg5, dg10, dspr, carry, conv, 52.0 / 252.0);   // daglig: skala interceptet
            idx *= (1 + rhat);
            rows.add(proxyRow(keys.get(i), idx));
        }
        try {
            java.nio.file.Files.write(java.nio.file.Path.of(OUT_PROXY), rows);
            System.out.printf("Skrev %s (%d dagliga punkter, %s -> %s) i app-cache-format%n",
                    OUT_PROXY, keys.size(), keys.get(0), keys.get(keys.size()-1));
        } catch (java.io.IOException e) {
            System.out.println("Kunde inte skriva " + OUT_PROXY + ": " + e.getMessage());
        }
    }

    /** En cache-rad: epoch (kl 12 UTC pa datumet), OHLC=adjclose=index, volym/utdelning 0. */
    static String proxyRow(LocalDate d, double v) {
        long ts = d.toEpochDay() * 86400L + 43200L;
        return String.format(java.util.Locale.US, "%d,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%.6f", ts, v, v, v, v, v, 0L, 0.0);
    }

    // ================================================================ //
    //  8. DEMODATA (kand "sanning" -> verifierar att skattningen traffar)
    // ================================================================ //
    static TreeMap<LocalDate, Double>[] makeDemo() {
        Random rng = new Random(0);
        List<LocalDate> idx = new ArrayList<>();
        LocalDate d = LocalDate.of(2017, 11, 1), end = LocalDate.of(2025, 12, 31);
        while (!d.isAfter(end)) {
            DayOfWeek w = d.getDayOfWeek();
            if (w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY) idx.add(d);
            d = d.plusDays(1);
        }
        int n = idx.size();
        // Tvafaktor-"sanning": 5y ror sig korrelerat men inte identiskt med 10y. Da underskattar
        // enfaktormodellen totalen (attenuering) medan key-rate-modellen aterskapar Doverlay+Dcash.
        double[] g10 = new double[n], g5 = new double[n], spread = new double[n];
        double s10 = 1.0, s5 = 0.6, pAcc = 0.35;
        for (int i = 0; i < n; i++) {
            double d10 = rng.nextGaussian() * 0.015;
            double d5  = 0.5 * d10 + rng.nextGaussian() * 0.010;   // 5y: delvis delad, delvis egen rorelse
            s10 += d10; s5 += d5; pAcc += rng.nextGaussian() * 0.004;
            g10[i] = s10; g5[i] = s5; spread[i] = Math.max(pAcc, 0.05);
        }
        double Doverlay = 8.0, Dcash = 3.0, conv = 60.0, feeDay = FEE_ANNUAL / 252.0;  // Dtot = 11
        TreeMap<LocalDate, Double> nav = new TreeMap<>(), g10M = new TreeMap<>(), g5M = new TreeMap<>(), spM = new TreeMap<>();
        double navv = 100.0;
        for (int i = 0; i < n; i++) {
            double dg10 = i == 0 ? 0 : (g10[i]-g10[i-1]) / 100.0;
            double dg5  = i == 0 ? 0 : (g5[i]-g5[i-1]) / 100.0;
            double dspr = i == 0 ? 0 : (spread[i]-spread[i-1]) / 100.0;
            double carry = (i == 0 ? (g10[0]+spread[0]) : (g10[i-1]+spread[i-1])) / 100.0 / 252.0;
            double r = -Doverlay*dg10 - Dcash*dg5 - Dcash*dspr + carry + 0.5*conv*dg10*dg10
                    - feeDay + rng.nextGaussian() * 0.0002;
            navv *= (1 + r);
            nav.put(idx.get(i), navv);
            g10M.put(idx.get(i), g10[i]);
            g5M.put(idx.get(i), g5[i]);
            spM.put(idx.get(i), spread[i]);
        }
        @SuppressWarnings("unchecked")
        TreeMap<LocalDate, Double>[] out = new TreeMap[]{nav, g10M, g5M, spM};
        return out;
    }

    // ================================================================ //
    //  MAIN
    // ================================================================ //
    public static void main(String[] args) throws IOException {
        TreeMap<LocalDate, Double> nav, g10, g5, spread;
        if (MODE.equals("demo")) {
            TreeMap<LocalDate, Double>[] dm = makeDemo();
            nav = dm[0]; g10 = dm[1]; g5 = dm[2]; spread = dm[3];
        } else {
            nav = readCsv(PATH_NAV);
            g10 = readCsv(PATH_G10);
            g5  = readCsv(PATH_G5);
            spread = minus(readCsv(PATH_B5), g5);    // covered-spread = 5y bostad - 5y stat
        }

        Panel p = buildWeekly(nav, g10, g5, spread);
        System.out.printf("Metod: %s%n", KEY_RATE ? "KEY-RATE (5y + 10y statsranta)" : "ENFAKTOR (10y statsranta)");
        System.out.printf("Estimeringsfonster: %s -> %s (%d veckor)%n%n",
                p.dates.get(0), p.dates.get(p.dates.size()-1), p.r.length);

        System.out.println("================ SPEC A (fri, med intercept) ================");
        Fit a = ols(p.r, designA(p), namesA(), HAC_LAGS);
        printFit(a);
        System.out.printf("Implicerad total-duration: %.2f ar  (mal ~%.1f)%n", impliedDuration(a), TARGET_DUR);
        System.out.printf("Intercept: %+.6f/v ~ %+.2f %%/ar (fee+residualcarry)%n%n", a.beta[0], a.beta[0]*52*100);

        System.out.println("======= SPEC B (carry-forankrad - anvands for backfill) =======");
        Fit b = ols(p.r, designB(p), namesB(), HAC_LAGS);
        printFit(b);
        System.out.printf("carry-koeff: %.2f  (bor ligga nara 1.0)%n", coefOf(b, "carry"));
        System.out.printf("Total-duration: %.2f ar   Kassabenets duration (-b_dspr): %.2f ar%n",
                impliedDuration(b), -coefOf(b, "dspr"));
        if (KEY_RATE)
            System.out.printf("  varav 5y-nyckelrate: %.2f ar,  10y-nyckelrate: %.2f ar%n", -coefOf(b, "dg5"), -coefOf(b, "dg10"));
        System.out.printf("Ljung-Box(%d) p: %.3f  (>0.05 = ingen kvarvarande autokorr.)%n", HAC_LAGS, ljungBoxP(b.resid, HAC_LAGS));
        System.out.printf("Korrelation fond vs modell (in-sample) = sqrt(R2) = %.4f%n", Math.sqrt(Math.max(b.r2, 0)));

        backfill(b, g10, g5, spread);
        if (MODE.equals("real")) writeAppProxy(b, g10, g5, spread);
    }
}