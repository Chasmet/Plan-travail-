package com.chasmet.plantravail;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class StreetResolver {
  private StreetResolver() {}

  private static String canonical(String text) {
    if (text == null) return "";
    String s =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.FRANCE)
            .replace('’', '\'')
            .replaceAll("[-']", " ")
            .replaceAll("\\s+", " ")
            .trim();
    return s.replaceFirst("\\s+orsay$", "").trim();
  }

  public static String normalize(String text) {
    return canonical(text)
        .replaceFirst("^(rue|avenue|av|boulevard|bd|chemin|allee|place|impasse|route)\\s+", "");
  }

  public static List<String> candidates(String query, List<String> names) {
    String wanted = normalize(query);
    List<String> exact = new ArrayList<>(), partial = new ArrayList<>();
    if (wanted.isEmpty()) return exact;
    for (String name : new LinkedHashSet<>(names))
      if (canonical(name).equals(canonical(query))) exact.add(name);
    if (!exact.isEmpty()) {
      Collections.sort(exact);
      return exact;
    }
    for (String name : new LinkedHashSet<>(names)) {
      String candidate = normalize(name);
      if (candidate.equals(wanted)) exact.add(name);
      else if (!candidate.isEmpty() && candidate.contains(wanted)) partial.add(name);
    }
    Collections.sort(exact);
    Collections.sort(partial);
    return exact.isEmpty() ? partial : exact;
  }

  public static List<String> names(List<Street> streets) {
    LinkedHashSet<String> names = new LinkedHashSet<>();
    if (streets != null) for (Street street : streets) names.add(street.getName());
    return new ArrayList<>(names);
  }

  public static String resolve(String query, List<Street> streets) {
    if (streets == null || streets.isEmpty())
      throw new IllegalStateException("Catalogue des rues indisponible ; commande conservée");
    List<String> found = candidates(query, names(streets));
    if (found.isEmpty())
      throw new IllegalArgumentException("Rue introuvable dans Orsay : " + query);
    if (found.size() != 1)
      throw new IllegalArgumentException(
          "Plusieurs rues possibles pour « "
              + query
              + " » : "
              + android.text.TextUtils.join(", ", found));
    return found.get(0);
  }
}
