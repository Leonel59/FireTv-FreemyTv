package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Settings changed on the TV itself (Free My TV > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** name, latitude, longitude */
    static final String[][] CITIES = {
        {"Aracaju", "-10.9472", "-37.0731"}, {"Belém", "-1.4558", "-48.4902"}, {"Belo Horizonte", "-19.9167", "-43.9345"},
        {"Boa Vista", "2.8235", "-60.6758"}, {"Brasília", "-15.7939", "-47.8828"}, {"Campo Grande", "-20.4697", "-54.6201"},
        {"Cuiabá", "-15.6014", "-56.0979"}, {"Curitiba", "-25.4284", "-49.2733"}, {"Florianópolis", "-27.5954", "-48.5480"},
        {"Fortaleza", "-3.7319", "-38.5267"}, {"Goiânia", "-16.6869", "-49.2648"}, {"João Pessoa", "-7.1195", "-34.8450"},
        {"Macapá", "0.0349", "-51.0694"}, {"Maceió", "-9.6658", "-35.7353"}, {"Manaus", "-3.1190", "-60.0217"},
        {"Natal", "-5.7945", "-35.2110"}, {"Palmas", "-10.1689", "-48.3317"}, {"Porto Alegre", "-30.0346", "-51.2177"},
        {"Porto Velho", "-8.7612", "-63.9004"}, {"Recife", "-8.0476", "-34.8770"}, {"Rio Branco", "-9.9747", "-67.8100"},
        {"Rio de Janeiro", "-22.9068", "-43.1729"}, {"Salvador", "-12.9777", "-38.5016"}, {"São Luís", "-2.5307", "-44.3068"},
        {"São Paulo", "-23.5505", "-46.6333"}, {"Teresina", "-5.0892", "-42.8019"}, {"Vitória", "-20.3155", "-40.3128"},
        {"Lisboa", "38.7223", "-9.1393"}, {"New York", "40.7128", "-74.0060"},
    };

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
    }

    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    // MARK: - Language and units

    /** "auto", "pt" or "en". */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.startsWith("pt") ? "pt" : v.startsWith("en") ? "en" : "auto";
    }

    static void setLanguage(String v) { put("language", v); Ui.en = Ui.english(); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = sp().getString("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { put("temperature", v); }

    /** "auto" (dark from sunset to sunrise), "light" or "dark". */
    static String theme() {
        String v = sp().getString("theme", "auto");
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    static void setTheme(String v) { put("theme", v); }

    // MARK: - City

    static String city() { return sp().getString("city", Config.CITY).trim(); }
    static String latitude() { return sp().getString("latitude", Config.LATITUDE); }
    static String longitude() { return sp().getString("longitude", Config.LONGITUDE); }

    /** Index in CITIES of the current city, or -1 (a city typed in config.properties). */
    static int cityIndex() {
        String name = city();
        for (int i = 0; i < CITIES.length; i++) if (CITIES[i][0].equalsIgnoreCase(name)) return i;
        return -1;
    }

    static void setCity(int i) {
        sp().edit().putString("city", CITIES[i][0]).putString("latitude", CITIES[i][1]).putString("longitude", CITIES[i][2]).apply();
        version++;
    }

    // MARK: - Widgets and coins

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }

    static boolean quotes() { return sp().getBoolean("quotes", true); }
    static void setQuotes(boolean on) { put("quotes", on); }

    static boolean coin(int i) { return sp().getBoolean("coin" + i, true); }
    static void setCoin(int i, boolean on) { put("coin" + i, on); }
}
