package com.imin.iminapi.predictor.sources.openevents;

import com.imin.iminapi.util.EventNormalization;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Cities with open event listings, from {@code predictor/open-events-v1.yaml}. Any invalid value throws
 * {@link IllegalStateException} naming the key, so a bad file stops boot. Only agendas checked live ship.
 */
public record OpenEventCities(int version, LocalDate verifiedOn, Map<String, City> byKey) {

    public static final String LOCATION = "classpath:predictor/open-events-v1.yaml";
    /** V165 {@code open_event_occurrence.city_key VARCHAR(100)}. */
    static final int MAX_CITY_KEY = 100;
    /** Licences an OpenAgenda agenda may carry: the part of the table CHECK that OpenAgenda's terms grant. */
    static final Set<String> OPENAGENDA_LICENCES = Set.of("Licence Ouverte 2.0");
    static final String QFAP_CITY = "paris";
    private static final Set<String> ROOT_KEYS = Set.of("version", "verified_on", "cities");
    private static final Set<String> CITY_KEYS = Set.of("country", "aliases", "openagenda", "quefaireaparis");
    private static final Set<String> AGENDA_KEYS = Set.of("uid", "slug", "name", "licence");
    private static final String FILE = "predictor open-events";

    public record Agenda(long uid, String slug, String name, String licence) {}

    public record City(String key, String country, List<String> aliases, List<Agenda> openagenda,
                       boolean quefaireaparis) {
        public City {
            aliases = List.copyOf(aliases);
            openagenda = List.copyOf(openagenda);
        }
    }

    public OpenEventCities {
        byKey = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byKey));
    }

    public static OpenEventCities load(ResourceLoader resources) {
        Resource resource = resources.getResource(LOCATION);
        if (!resource.exists()) throw new IllegalStateException(FILE + ": file not found: " + LOCATION);
        try (InputStream in = resource.getInputStream()) {
            return parse(in);
        } catch (IOException e) {
            throw new IllegalStateException(FILE + ": cannot read " + LOCATION, e);
        }
    }

    public static OpenEventCities parse(InputStream in) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object raw;
        try {
            raw = new Yaml(new SafeConstructor(options)).load(in);
        } catch (YAMLException e) {
            throw new IllegalStateException(FILE + ": invalid YAML: " + e.getMessage(), e);
        }
        Map<?, ?> root = mapping(raw, "root");
        onlyKeys(root, ROOT_KEYS, "root");
        if (!(root.get("version") instanceof Integer version) || version < 1) {
            throw new IllegalStateException(FILE + " version: must be an integer of at least 1");
        }
        LocalDate verifiedOn = date(root.get("verified_on"));
        Map<String, City> cities = new LinkedHashMap<>();
        Map<String, String> aliasOwner = new HashMap<>();
        Set<Long> uids = new HashSet<>();
        for (Map.Entry<?, ?> e : mapping(root.get("cities"), "cities").entrySet()) {
            String key = String.valueOf(e.getKey());
            City city = city(key, mapping(e.getValue(), "cities." + key), uids);
            for (String alias : city.aliases()) {
                String other = aliasOwner.putIfAbsent(alias, key);
                if (other != null) {
                    throw new IllegalStateException(FILE + " cities." + key + ".aliases: alias '" + alias
                            + "' is already used by " + other);
                }
            }
            cities.put(key, city);
        }
        return new OpenEventCities(version, verifiedOn, cities);
    }

    public List<City> cities() {
        return List.copyOf(byKey.values());
    }

    public Optional<City> city(String key) {
        return Optional.ofNullable(key == null ? null : byKey.get(key));
    }

    /** The city of {@code country} whose key or alias equals the normalised {@code cityKey}; empty for null or blank. */
    public Optional<City> resolve(String cityKey, String country) {
        if (cityKey == null || cityKey.isBlank()) return Optional.empty();
        String key = EventNormalization.cityKey(cityKey);
        return byKey.values().stream().filter(c -> c.country().equals(country))
                .filter(c -> c.key().equals(key) || c.aliases().contains(key)).findFirst();
    }

    private static City city(String key, Map<?, ?> m, Set<Long> uids) {
        String where = "cities." + key;
        if (key.length() > MAX_CITY_KEY) {
            throw new IllegalStateException(FILE + " " + where.substring(0, 20) + "…: city key longer than " + MAX_CITY_KEY);
        }
        if (!key.equals(EventNormalization.cityKey(key)) || key.isBlank()) {
            throw new IllegalStateException(FILE + " " + where + ": city key must be normalised (lower case, single spaces)");
        }
        onlyKeys(m, CITY_KEYS, where);
        if (!"FR".equals(m.get("country"))) {
            throw new IllegalStateException(FILE + " " + where + ".country: only FR is supported, got '" + m.get("country") + "'");
        }
        List<String> aliases = new ArrayList<>();
        if (!(m.get("aliases") instanceof List<?> rawAliases) || rawAliases.isEmpty()) {
            throw new IllegalStateException(FILE + " " + where + ".aliases: must be a non-empty list");
        }
        for (Object a : rawAliases) {
            String alias = a == null ? "" : String.valueOf(a);
            if (alias.isBlank() || !alias.equals(EventNormalization.cityKey(alias))) {
                throw new IllegalStateException(FILE + " " + where + ".aliases: '" + alias + "' must be normalised");
            }
            if (aliases.contains(alias)) {
                throw new IllegalStateException(FILE + " " + where + ".aliases: alias '" + alias + "' is listed twice");
            }
            aliases.add(alias);
        }
        boolean qfap = false;
        if (m.containsKey("quefaireaparis")) {
            if (!(m.get("quefaireaparis") instanceof Boolean b)) {
                throw new IllegalStateException(FILE + " " + where + ".quefaireaparis: must be true or false");
            }
            qfap = b;
        }
        if (qfap && !QFAP_CITY.equals(key)) {
            throw new IllegalStateException(FILE + " " + where + ".quefaireaparis: only allowed on " + QFAP_CITY);
        }
        List<Agenda> agendas = new ArrayList<>();
        if (m.containsKey("openagenda")) {
            if (!(m.get("openagenda") instanceof List<?> rawAgendas)) {
                throw new IllegalStateException(FILE + " " + where + ".openagenda: must be a list");
            }
            for (int i = 0; i < rawAgendas.size(); i++) {
                agendas.add(agenda(mapping(rawAgendas.get(i), where + ".openagenda[" + i + "]"),
                        where + ".openagenda[" + i + "]", uids));
            }
        }
        if (!qfap && agendas.isEmpty()) {
            throw new IllegalStateException(FILE + " " + where + ": no source (needs an openagenda agenda or quefaireaparis)");
        }
        return new City(key, "FR", aliases, agendas, qfap);
    }

    private static Agenda agenda(Map<?, ?> m, String where, Set<Long> uids) {
        onlyKeys(m, AGENDA_KEYS, where);
        Object rawUid = m.get("uid");
        long uid = rawUid instanceof Integer i ? i : rawUid instanceof Long l ? l : -1;
        if (uid <= 0) throw new IllegalStateException(FILE + " " + where + ".uid: must be a positive integer");
        if (!uids.add(uid)) throw new IllegalStateException(FILE + " " + where + ".uid: " + uid + " is listed twice");
        String slug = text(m.get("slug"), where + ".slug");
        String name = text(m.get("name"), where + ".name");
        String licence = text(m.get("licence"), where + ".licence");
        if (!OPENAGENDA_LICENCES.contains(licence) || !OpenEventSource.LICENCES.contains(licence)) {
            throw new IllegalStateException(FILE + " " + where + ".licence: '" + licence + "' is not one of "
                    + OPENAGENDA_LICENCES);
        }
        return new Agenda(uid, slug, name, licence);
    }

    private static String text(Object raw, String where) {
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new IllegalStateException(FILE + " " + where + ": is missing or blank");
        }
        return s.strip();
    }

    // SnakeYAML reads an unquoted 2026-10-01 as a timestamp; a quoted one stays text.
    private static LocalDate date(Object raw) {
        if (raw instanceof Date d) return d.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        if (raw instanceof String s) {
            try {
                return LocalDate.parse(s.strip());
            } catch (DateTimeParseException e) {
                throw new IllegalStateException(FILE + " verified_on: '" + s + "' is not an ISO date");
            }
        }
        throw new IllegalStateException(FILE + " verified_on: is missing");
    }

    private static Map<?, ?> mapping(Object raw, String where) {
        if (!(raw instanceof Map<?, ?> m)) throw new IllegalStateException(FILE + " " + where + ": expected a mapping");
        return m;
    }

    private static void onlyKeys(Map<?, ?> m, Set<String> allowed, String where) {
        for (Object k : m.keySet()) {
            if (!allowed.contains(String.valueOf(k))) {
                throw new IllegalStateException(FILE + " " + where + ": unknown key '" + k + "'");
            }
        }
    }
}
