package org.unicode.jsp;

import com.ibm.icu.impl.Row.R4;
import com.ibm.icu.impl.UnicodeMap;
import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
import com.ibm.icu.text.Collator;
import com.ibm.icu.text.Normalizer;
import com.ibm.icu.text.Normalizer2;
import com.ibm.icu.text.NumberFormat;
import com.ibm.icu.text.RuleBasedCollator;
import com.ibm.icu.text.SpoofChecker;
import com.ibm.icu.text.StringTransform;
import com.ibm.icu.text.Transform;
import com.ibm.icu.text.Transliterator;
import com.ibm.icu.text.UTF16;
import com.ibm.icu.text.UnicodeSet;
import com.ibm.icu.text.UnicodeSetIterator;
import com.ibm.icu.util.ULocale;
import com.ibm.icu.util.VersionInfo;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.unicode.cldr.tool.TablePrinter;
import org.unicode.cldr.util.Predicate;
import org.unicode.cldr.util.UnicodeSetPrettyPrinter;
import org.unicode.idna.Idna.IdnaType;
import org.unicode.idna.Idna2003;
import org.unicode.idna.Idna2008;
import org.unicode.idna.IdnaTypes;
import org.unicode.idna.Punycode;
import org.unicode.idna.Uts46;
import org.unicode.props.DerivedPropertyStatus;
import org.unicode.props.IndexUnicodeProperties;
import org.unicode.props.UcdProperty;
import org.unicode.props.UcdPropertyValues.Age_Values;
import org.unicode.props.UnicodeProperty;
import org.unicode.props.UnicodeProperty.UnicodeMapProperty;
import org.unicode.text.utility.Settings;
import org.unicode.text.utility.Utility;

// For dependency management, it might be useful to split this omnibus class into
// pieces by topic, such as collation utilities vs. IDNA utilities etc.
public class UnicodeUtilities {

    private static final Collator COLLATOR = Collator.getInstance(new ULocale("en-u-co-emoji"));
    static final UnicodeSet OFF_LIMITS =
            new UnicodeSet(UnicodeProperty.getUNASSIGNED())
                    .addAll(UnicodeProperty.PRIVATE_USE)
                    .addAll(UnicodeProperty.SURROGATE)
                    .freeze();
    static final UnicodeSet NONCHAR =
            new UnicodeSet(OFF_LIMITS)
                    .addAll(new UnicodeSet("[:Cc:]"))
                    .removeAll(new UnicodeSet("[:whitespace:]"))
                    .freeze();

    static Transliterator toHTML;
    static Transliterator toHTMLInput;
    static String HTML_RULES_CONTROLS;

    static {
        String BASE_RULES =
                "'<' > '&lt;' ;"
                        + "'<' < '&'[lL][Tt]';' ;"
                        + "'&' > '&amp;' ;"
                        + "'&' < '&'[aA][mM][pP]';' ;"
                        + "'>' < '&'[gG][tT]';' ;"
                        + "'\"' < '&'[qQ][uU][oO][tT]';' ; "
                        + "'' < '&'[aA][pP][oO][sS]';' ; ";

        String CONTENT_RULES = "'>' > '&gt;' ;";

        String HTML_RULES = BASE_RULES + CONTENT_RULES + "'\"' > '&quot;' ; '' > '&apos;' ;";

        toHTMLInput = Transliterator.createFromRules("any-xml", HTML_RULES, Transliterator.FORWARD);

        HTML_RULES_CONTROLS =
                HTML_RULES
                        // + "\\u0000 > \uFFFD ; "
                        + "[\\uD800-\\uDB7F] > '<span class=\"high-surrogate\"><span>'\uFFFD'</span></span>' ; "
                        + "[\\uDB80-\\uDBFF] > '<span class=\"private-surrogate\"><span>'\uFFFD'</span></span>' ; "
                        + "[\\uDC00-\\uDFFF] > '<span class=\"low-surrogate\"><span>'\uFFFD'</span></span>' ; "
                        + "([[:cn:][:co:][:cc:]-[:White_Space:]]) > '<span class=\"control\">'$1'</span>' ; ";
        toHTML =
                Transliterator.createFromRules(
                        "any-xml", HTML_RULES_CONTROLS, Transliterator.FORWARD);
    }

    public static String toHTML(String input) {
        return toHTML.transliterate(input);
    }

    public static String toHTMLInput(String input) {
        return toHTMLInput.transliterate(input);
    }

    static Transliterator UNICODE = Transliterator.getInstance("hex-any");

    static final int IDNA_TYPE_LIMIT = 4;

    //  static final Map<IdnaType,UnicodeSet> idnaTypeSet = new TreeMap<IdnaType,UnicodeSet>();
    //  static {
    //    for (IdnaType i : IdnaType.values()) {
    //      idnaTypeSet.put(i, new UnicodeSet());
    //    }
    //  }

    public static UnicodeSet IGNORE_IN_IDNA_DIFF =
            new UnicodeSet("[[\\u0000-\\u007F][:Cc:][:Cn:][:Co:][:Cs:]]").freeze();

    public static UnicodeMap<String> getIdnaDifferences(
            UnicodeSet remapped, UnicodeSet overallAllowed) {
        UnicodeMap<String> result = new UnicodeMap<String>();
        UnicodeSet valid2008 = Idna2008.getIdna2008Valid();

        for (int i = 0; i <= 0x10FFFF; ++i) {
            if ((i & 0xFFF) == 0) System.out.println(Utility.hex(i));
            if (i == 0x20000) {
                System.out.println("debug");
            }
            if (IGNORE_IN_IDNA_DIFF.contains(i)) continue;
            boolean isNew = UCharacter.getAge(i).compareTo(VersionInfo.UNICODE_3_2) > 0;
            String age = isNew ? "v4.0-5.2" : "v3.2";
            IdnaType idna2003 = Idna2003.getIDNA2003Type(i);
            IdnaType tr46 = Uts46.SINGLETON.getType(i);
            if (isNew) { // skip
            } else if ((tr46 == IdnaType.mapped || idna2003 == IdnaType.mapped)
                    && tr46 != IdnaType.disallowed
                    && idna2003 != IdnaType.disallowed) {
                remapped.add(i);
            }
            // TestStatus testResult = valid2008.contains(i);
            IdnaType idna2008 = valid2008.contains(i) ? IdnaType.valid : IdnaType.disallowed;
            String iClass =
                    age
                            + "\t"
                            + getShortName(idna2003)
                            + "\t"
                            + getShortName(tr46)
                            + "\t"
                            + getShortName(idna2008);
            result.put(i, iClass);
        }
        return result.freeze();
    }

    static String getShortName(IdnaType tr46) {
        // TODO Auto-generated method stub
        return UCharacter.toTitleCase(
                tr46 == IdnaType.valid
                        ? "Valid"
                        : tr46 == IdnaType.ignored || tr46 == IdnaType.mapped
                                ? "Mapped/Ignored"
                                : tr46.toString(),
                null);
    }

    static final UnicodeSet MARK = new UnicodeSet("[:M:]").freeze();

    static String getXStringPropertyValue(
            int propertyEnum, int codepoint, int nameChoice, Normalizer.Mode compat) {
        if (compat == null || Normalizer.isNormalized(codepoint, compat, 0)) {
            return Common.getXStringPropertyValue(propertyEnum, codepoint, nameChoice);
        }
        String s = Common.MyNormalize(codepoint, compat);
        int cp;
        String lastPart = null;
        for (int i = 0; i < s.length(); i += UTF16.getCharCount(cp)) {
            cp = UTF16.charAt(s, i);
            String part = Common.getXStringPropertyValue(propertyEnum, cp, nameChoice);
            if (lastPart == null) {
                lastPart = part;
            } else if (!lastPart.equals(part)) {
                if (propertyEnum == UProperty.SCRIPT && MARK.contains(cp)) {
                    continue;
                }
                return "Mixed";
            }
        }
        return lastPart;
    }

    static UnicodeSet COMMON_USE_SCRIPTS =
            new UnicodeSet(
                            "[[:script=Zyyy:] [:script=Zinh:] [:script=Arab:] [:script=Armn:]"
                                    + " [:script=Beng:] [:script=Bopo:] [:script=Cans:] [:script=Cyrl:] [:script=Deva:] [:script=Ethi:]"
                                    + " [:script=Geor:] [:script=Grek:] [:script=Gujr:] [:script=Guru:] [:script=Hani:] [:script=Hang:]"
                                    + " [:script=Hebr:] [:script=Hira:] [:script=Knda:] [:script=Kana:] [:script=Khmr:] [:script=Laoo:]"
                                    + " [:script=Latn:] [:script=Mlym:] [:script=Mong:] [:script=Mymr:] [:script=Orya:] [:script=Sinh:] "
                                    + "[:script=Taml:] [:script=Telu:] [:script=Tfng:] [:script=Thaa:] [:script=Thai:] [:script=Tibt:] [:script=Yiii:]]")
                    .freeze();

    static UnicodeSet LITURGICAL =
            new UnicodeSet(
                    "[\u0615\u0617-\u061A\u0671\u06D6-\u06ED\u08F0-\u08F3[:sc=coptic:]"
                            + "\u1CD0-\u1CF2\u214F]");
    static UnicodeSet DEPRECATED = new UnicodeSet("[:deprecated:]").freeze();

    static int getXPropertyEnum(String propertyAlias) {
        int extra = Common.XPROPERTY_NAMES.indexOf(propertyAlias.toLowerCase(Locale.ENGLISH));
        if (extra != -1) {
            return UProperty.STRING_LIMIT + extra;
        }
        return UCharacter.getPropertyEnum(propertyAlias);
    }

    //  protected static boolean getIdnaProperty(String propertyValue,
    //          UnicodeSet result) {
    //    try {
    //      String lowercase = propertyValue.toLowerCase(Locale.ENGLISH);
    //      IdnaType i = lowercase.equals("output") ? IdnaType.valid
    //              : lowercase.equals("remapped") ? IdnaType.mapped
    //                      : IdnaType.valueOf(lowercase);
    //      result.clear().addAll(idnaTypeSet.get(i));
    //      return true;
    //    } catch (Exception e) {
    //      throw new IllegalArgumentException("Error with <" + propertyValue + ">", e);
    //    }
    //  }

    static boolean getBinaryValue(String propertyValue) {
        boolean invert;
        if (propertyValue.length() == 0
                || propertyValue.equalsIgnoreCase("true")
                || propertyValue.equalsIgnoreCase("t")
                || propertyValue.equalsIgnoreCase("yes")
                || propertyValue.equalsIgnoreCase("y")) {
            invert = false;
        } else if (propertyValue.equalsIgnoreCase("false")
                || propertyValue.equalsIgnoreCase("f")
                || propertyValue.equalsIgnoreCase("no")
                || propertyValue.equalsIgnoreCase("n")) {
            invert = true;
        } else {
            throw new IllegalArgumentException(
                    "PropertyValue must be empty (= T) or one of: True, T, False, F");
        }
        return invert;
    }

    public static boolean equals(CharSequence inbuffer, CharSequence outbuffer) {
        if (inbuffer.length() != outbuffer.length()) {
            return false;
        }
        for (int i = inbuffer.length() - 1; i >= 0; --i) {
            if (inbuffer.charAt(i) != outbuffer.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    static XPropertyFactory getFactory() {
        return XPropertyFactory.make();
    }

    static NumberFormat numberFormat =
            NumberFormat.getInstance(ULocale.ENGLISH, NumberFormat.NUMBERSTYLE);

    static {
        numberFormat.setGroupingUsed(true);
    }

    public static void showSetMain(
            UnicodeSet a,
            boolean showDevProperties,
            CodePointShower codePointShower,
            Appendable out)
            throws IOException {
        if (codePointShower.groupingProps.isEmpty()) {
            showSet(a, showDevProperties, codePointShower, out);
            return;
        }

        UnicodeMap<String> map = new UnicodeMap<String>();
        for (String s : a) {
            map.put(s, codePointShower.getPropString(codePointShower.groupingProps, s, false));
        }

        TreeSet<String> sorted = new TreeSet<String>(UnicodeSetUtilities.MAIN_COLLATOR);
        sorted.addAll(map.values());
        int length = codePointShower.groupingProps.size();
        String[] propsOld = new String[codePointShower.groupingProps.size()];
        for (int i = 0; i < propsOld.length; ++i) {
            propsOld[i] = "";
        }
        int lastLevel = -1;
        for (String s : sorted) {
            String[] props2 = s.split("; ");
            int level = getFirstDiff(propsOld, props2);
            // out.append("// level: " + level + ", lastLevel: " + lastLevel + "\n");
            // if higher, back off
            if (lastLevel >= 0) {
                for (int i = level; i < length; ++i) {
                    out.append("</blockquote>\n");
                }
            }
            lastLevel = level;
            UnicodeSet items = map.getSet(s);
            for (int i = lastLevel; i < length; ++i) {
                out.append(
                        "<h2 class='L"
                                + (i + 5 - length)
                                + "'>"
                                + props2[i]
                                + (i == length - 1
                                        ? " <div class='ri'>items: "
                                                + numberFormat.format(items.size())
                                        : "</div>")
                                + "</h2><blockquote>\n");
            }
            showSet(items, showDevProperties, codePointShower, out);
            for (int i = 0; i < propsOld.length; ++i) {
                propsOld[i] = props2[i];
            }
        }
        for (int i = 0; i <= lastLevel; ++i) {
            out.append("</blockquote>\n");
        }
    }

    static int getFirstDiff(String[] a, String[] b) {
        for (int i = 0; i < a.length; ++i) {
            if (!a[i].equals(b[i])) {
                return i;
            }
        }
        return a.length;
    }

    //  static getPropNames() {
    //    return getFactory().getAvailableNames();
    //  }

    public static String getStringProperties(
            UnicodeProperty prop, String s, String separator, boolean getShortest) {
        // check for single code point, later
        if (prop instanceof UnicodeMapProperty) {
            Object value = prop.getUnicodeMap().get(s);
            if (value != null) {
                return (String) value;
            }
        }

        StringBuilder builder = new StringBuilder();
        int cp;
        String last = null;
        for (int i = 0; i < s.length(); i += Character.charCount(cp)) {
            cp = s.codePointAt(i);
            final String current = prop.getValue(cp, getShortest);
            if (!Objects.equals(current, last)) {
                if (i != 0) {
                    builder.append(separator);
                }
                builder.append(current);
            }
            last = current;
        }
        return builder.toString();
    }

    /*jsp*/
    public static void showSet(
            UnicodeSet inputSetRaw,
            boolean showDevProperties,
            CodePointShower codePointShower,
            Appendable out)
            throws IOException {
        if (codePointShower.doTable) {
            out.append("<table width='100%'>");
        }
        if (inputSetRaw.getRangeCount() > 10000) {
            if (codePointShower.doTable) {
                out.append("<tr><td colSpan='4'>");
            }
            out.append("<i>Too many to list individually</i>\n");
            if (codePointShower.doTable) {
                out.append("</td></tr>");
            }
        } else if (codePointShower.abbreviate) {
            codePointShower.showAbbreviated(inputSetRaw, out);
        } else {
            LinkedHashMap<String, UnicodeSet> items = new LinkedHashMap<String, UnicodeSet>();
            String specials = "Unassigned, Private use, or Surrogates";

            var specialsForDisplay = UnicodeProperty.getSPECIALS();
            if (!showDevProperties) {
                // UnicodeProperty uses the freshest (smallest) set of unassigned code points; if we
                // are not showing the corresponding block and character names, put the
                // newly-assigned characters in the unassigned pile for display.
                specialsForDisplay = specialsForDisplay.cloneAsThawed();
                getFactory()
                        .getProperty("General_Category")
                        .getSet("Unassigned", specialsForDisplay);
            }
            UnicodeSet specialSet = new UnicodeSet(inputSetRaw).retainAll(specialsForDisplay);
            UnicodeSet inputSet =
                    specialSet.size() == 0
                            ? inputSetRaw
                            : new UnicodeSet(inputSetRaw).removeAll(specialsForDisplay);
            if (specialSet.size() != 0) {
                items.put(specials, specialSet);
            }
            for (UnicodeSetIterator it = new UnicodeSetIterator(inputSet); it.next(); ) {
                int s = it.codepoint;
                if (s == UnicodeSetIterator.IS_STRING) {
                    String newBlock = "Strings";
                    UnicodeSet set = items.get(newBlock);
                    if (set == null) items.put(newBlock, set = new UnicodeSet());
                    set.add(it.string);
                } else {
                    String devBlock =
                            getFactory().getProperty("Udev:Block").getValue(s).replace('_', ' ');
                    String block = getFactory().getProperty("Block").getValue(s).replace('_', ' ');
                    String newBlock =
                            showDevProperties && !devBlock.equals(block)
                                    ? "<a class='changed' href='list-unicodeset.jsp?a=\\p{U"
                                            + Settings.latestVersion
                                            + Settings.latestVersionPhase
                                            + ":Block="
                                            + devBlock
                                            + "}'>"
                                            + devBlock
                                            + "</a>"
                                    : "<a href='list-unicodeset.jsp?a=\\p{Block="
                                            + block
                                            + "}'>"
                                            + block
                                            + "</a>";
                    String devSubhead =
                            getFactory().getProperty("Udev:Names_List_Subheader").getValue(s);
                    String subhead = getFactory().getProperty("Names_List_Subheader").getValue(s);
                    String newSubhead =
                            showDevProperties && devSubhead != null && !devSubhead.equals(subhead)
                                    ? "<a class='changed' href='list-unicodeset.jsp?a=\\p{U"
                                            + Settings.latestVersion
                                            + Settings.latestVersionPhase
                                            + ":Names_List_Subheader="
                                            + devSubhead
                                            + "}'>"
                                            + devSubhead
                                            + "</a>"
                                    : subhead != null
                                            ? "<a href='list-unicodeset.jsp?a=\\p{Names_List_Subheader="
                                                    + subhead
                                                    + "}'>"
                                                    + subhead
                                                    + "</a>"
                                            : "<u>no subheader</u>";
                    newBlock = newBlock + " \u2014 <i>" + newSubhead + "</i>";
                    UnicodeSet set = items.get(newBlock);
                    if (set == null) items.put(newBlock, set = new UnicodeSet());
                    set.add(s);
                }
            }

            for (String newBlock : items.keySet()) {
                UnicodeSet set = items.get(newBlock);
                if (codePointShower.doTable) {
                    out.append("<tr><td colSpan='3'>");
                }
                out.append(
                        "<h3>"
                                + newBlock
                                + "</b> <div class='ri'>items: "
                                + numberFormat.format(set.size())
                                + "</div></h3>\n");
                if (codePointShower.doTable) {
                    out.append("</td></tr>");
                }

                if (set.size() > 1000 || newBlock == specials) {
                    codePointShower.showAbbreviated(set, out);
                } else if (codePointShower.collate) {
                    TreeSet<String> sorted =
                            set.addAllTo(new TreeSet<String>(UnicodeSetUtilities.MAIN_COLLATOR));
                    for (String s : sorted) {
                        codePointShower.showString(s, ", ", out);
                    }
                } else {
                    for (UnicodeSetIterator it = new UnicodeSetIterator(set); it.next(); ) {
                        int s = it.codepoint;
                        if (s == UnicodeSetIterator.IS_STRING) {
                            codePointShower.showString(it.string, ", ", out);
                        } else {
                            codePointShower.showCodePoint(s, out);
                        }
                    }
                }
            }
        }
        if (codePointShower.doTable) {
            out.append("</table>");
        }
    }

    public static String getIdentifier(
            String script, boolean showDevProperties, List<String> originalParameters) {
        StringBuilder result = new StringBuilder();
        UnicodeProperty scriptProp = getFactory().getProperty("sc");
        UnicodeSet scriptSet;
        scriptSet = scriptProp.getSet(script);
        scriptSet.removeAll(NONCHAR);
        if (scriptSet.size() == 0) {
            result.append(
                    "<p><i>Illegal script:</i> "
                            + toHTML(script)
                            + ". Please pick one of the following:</p>\n<p>");
            String last = null;
            TreeSet<String> sorted = new TreeSet<String>(col);
            sorted.addAll(scriptProp.getAvailableValues());
            for (String s : sorted) {
                scriptSet = scriptProp.getSet(s);
                scriptSet.removeAll(NONCHAR);
                if (scriptSet.size() == 0) {
                    continue;
                }
                String name = toHTML(s);
                if (last == null) {
                    // nothing
                } else if (last.charAt(0) == s.charAt(0)) {
                    result.append(' ');
                } else {
                    result.append("</p><p>");
                }
                result.append(
                        "<a target='id' href='identifier.jsp?a=" + name + "'>" + name + "</a>");
                last = s;
            }
            result.append("</p>\n");
            return result.toString();
        }
        try {
            UnicodeSet allowed = new UnicodeSet(scriptSet).retainAll(XIDModifications.getAllowed());
            UnicodeSet restricted =
                    new UnicodeSet(scriptSet).removeAll(XIDModifications.getAllowed());
            result.append("<h2>Allowed</h2>");
            if (allowed.size() == 0) {
                result.append("<i>none</i>");
            } else {
                showSet(
                        allowed,
                        showDevProperties,
                        new CodePointShower(
                                "", "", showDevProperties, true, false, false, originalParameters),
                        result);
            }

            if (restricted.size() == 0) {
                result.append("<h2>Restricted</h2>");
                result.append("<i class='redName'>none</i>");
            } else {
                final UnicodeMap<String> types = XIDModifications.getTypes();
                for (String reason : types.values()) {
                    UnicodeSet shard = types.getSet(reason);
                    UnicodeSet items = new UnicodeSet(restricted).retainAll(shard);
                    if (items.size() != 0) {
                        result.append(
                                "<h2>Restricted - <span class='redName'>"
                                        + reason
                                        + "</span></h2>");
                        showSet(
                                items,
                                showDevProperties,
                                new CodePointShower(
                                                "",
                                                "",
                                                showDevProperties,
                                                true,
                                                false,
                                                false,
                                                originalParameters)
                                        .setRestricted(true),
                                result);
                    }
                }
            }
            return result.toString();
        } catch (IOException e) {
            return "<i>Internal Error</i>";
        }
    }

    private static UnicodeSet RTL = new UnicodeSet("[[:bc=R:][:bc=AL:]]");

    private static String showCodePoint(int codepoint) {
        return showCodePoint(UTF16.valueOf(codepoint));
    }

    private static String showCodePoint(String s) {
        String literal = getLiteral(s);
        return "<a target='c' href='list-unicodeset.jsp?a="
                + toHTML.transliterate(UtfParameters.fixQuery(s))
                + "'>\u00a0"
                + literal
                + "\u00a0</a>";
    }

    private static String getLiteral(int codepoint) {
        return getLiteral(UTF16.valueOf(codepoint));
    }

    private static String getLiteral(String s) {
        String literal = toHTML.transliterate(s);
        if (RTL.containsSome(literal)) {
            literal = '\u200E' + literal + '\u200E';
        }
        return literal;
    }

    static class CodePointShower {

        public final boolean doTable;
        public final boolean showDevProperties;
        public final boolean abbreviate;
        public final boolean ucdFormat;
        public final boolean collate;
        public final List<UnicodeProperty> groupingProps;
        public final List<UnicodeProperty> infoProps;
        public final List<String> originalParameters;

        public boolean restricted;

        public CodePointShower setRestricted(boolean restricted) {
            this.restricted = restricted;
            return this;
        }

        public CodePointShower(
                String grouping,
                String info,
                boolean showDevProperties,
                boolean abbreviate,
                boolean ucdFormat,
                boolean collate,
                List<String> originalParameters) {
            this.groupingProps = getProps(grouping);
            this.infoProps = getProps(info);
            this.doTable = true; // !infoProps.isEmpty();
            this.showDevProperties = showDevProperties;
            this.abbreviate = abbreviate;
            this.ucdFormat = ucdFormat;
            this.collate = collate;
            this.originalParameters = originalParameters;
        }

        void showCodePoint(int codePoint, Appendable out) throws IOException {
            final String string = UTF16.valueOf(codePoint);
            String separator = ", ";
            showString(string, separator, out);
        }

        private void showString(final String string, String separator, Appendable out)
                throws IOException {
            if (doTable) {
                out.append("<tr>");
            }
            boolean hasJoiner = string.contains("\u200D");
            String literal = UnicodeUtilities.toHTML.transliterate(string);
            if (hasJoiner) {
                boolean hasJoiner2 = literal.contains("\u200D");
                if (hasJoiner2 != hasJoiner) {
                    int debug = 0;
                }
            }
            if (UnicodeUtilities.RTL.containsSome(literal)) {
                literal = '\u200E' + literal + '\u200E';
            }
            String name =
                    UnicodeUtilities.getName(string, separator, showDevProperties, false, false);
            literal = UnicodeSetUtilities.addEmojiVariation(literal);
            if (doTable) {
                out.append(
                        "<td class='charCell' width='3m'>\u00A0"
                                + literal
                                + "\u00A0</td>"
                                + "<td width='7m'>"
                                + UnicodeUtilities.getHex(
                                        string, separator, ucdFormat, originalParameters)
                                + "</td>"
                                + "<td"
                                + (restricted ? " class='redName'" : "")
                                + ">"
                                + name
                                + "</td>");
            } else if (ucdFormat) {
                out.append(
                        UnicodeUtilities.getHex(string, separator, ucdFormat, originalParameters)
                                + " ;\t"
                                + name);
            } else {
                // out.append("<div class='cx'>\u00A0" + literal + "\u00A0</div>" +
                // UnicodeUtilities.getHex(string, separator, ucdFormat) + " \t" + name);
                out.append(
                        "\u00A0"
                                + literal
                                + "\u00A0\t"
                                + UnicodeUtilities.getHex(
                                        string, separator, ucdFormat, originalParameters)
                                + " \t"
                                + name);
                if (hasJoiner) {
                    boolean hasJoiner2 = literal.contains("\u200D");
                    if (hasJoiner2 != hasJoiner) {
                        int debug = 0;
                    }
                }
            }
            if (!infoProps.isEmpty()) {
                int cp = string.codePointAt(0);
                // StringBuilder confusableString = displayConfusables(cp);
                if (doTable) {
                    out.append("<td align='right'>");
                } else {
                    out.append("; ");
                }
                out.append(getPropString(infoProps, string, true));
                if (doTable) {
                    out.append("</td>");
                }
            }
            if (doTable) {
                out.append("</tr>\n");
            } else {
                out.append("<br>\n");
            }
        }

        List<UnicodeProperty> getProps(String input) {
            List<UnicodeProperty> properties = new ArrayList();
            input = input.trim();
            String[] propNames = input.split("[;,\\s]\\s*");
            boolean getShortest = false;
            for (String s : propNames) {
                if (s.isEmpty()) {
                    continue;
                }
                try {
                    UnicodeProperty property = getFactory().getProperty(s);
                    String name = property.getName();
                    // trigger exception
                    name.charAt(0);
                    property.getValue(0, getShortest);
                    properties.add(property);
                } catch (Exception e) {
                    // ignore for now
                }
            }
            return Collections.unmodifiableList(properties);
        }

        private void showAbbreviated(UnicodeSet a, Appendable out) throws IOException {
            UnicodeUtilities.CodePointShower codePointShower = this;

            boolean haveStrings = false;
            for (UnicodeSetIterator it = new UnicodeSetIterator(a); it.nextRange(); ) {
                int s = it.codepoint;
                if (s == UnicodeSetIterator.IS_STRING) {
                    if (!haveStrings && codePointShower.doTable) {
                        out.append("<tr><td colSpan='3'>");
                        haveStrings = true;
                    }
                    out.append(UnicodeUtilities.showCodePoint(it.string)).append("\n");
                } else {
                    int end = it.codepointEnd;
                    if (end == s) {
                        codePointShower.showCodePoint(s, out);
                    } else if (end == s + 1) {
                        codePointShower.showCodePoint(s, out);
                        codePointShower.showCodePoint(end, out);
                    } else {
                        if (codePointShower.ucdFormat) {
                            out.append(
                                    UnicodeUtilities.getHex(
                                            s, codePointShower.ucdFormat, originalParameters));
                            out.append("..");
                            codePointShower.showCodePoint(end, out);
                        } else {
                            codePointShower.showCodePoint(s, out);
                            if (doTable) {
                                out.append(
                                        "<tr><td colSpan='4'>"
                                                + "\u2026{"
                                                + (end - s - 1)
                                                + "}\u2026</td</tr>");
                            } else {
                                out.append("\u2026{" + (end - s - 1) + "}\u2026");
                            }
                            codePointShower.showCodePoint(end, out);
                        }
                    }
                }
            }
            if (haveStrings && codePointShower.doTable) {
                out.append("</td></tr>");
            }
        }

        String getPropString(List<UnicodeProperty> props, String codePoints, boolean shortName) {
            StringBuilder builder = new StringBuilder();
            for (UnicodeProperty prop : props) {
                if (builder.length() != 0) {
                    builder.append("; ");
                }
                String name = prop.getName();
                if (shortName) {
                    List<String> aliases = prop.getNameAliases();
                    if (aliases != null && aliases.size() > 0) {
                        name = aliases.get(0);
                    }
                }
                builder.append(name)
                        .append("=")
                        .append(getStringProperties(prop, codePoints, ", ", shortName));
            }
            return builder.toString();
        }

        //        String getPropString(List<UnicodeProperty> props, int codePoint) {
        //            StringBuilder builder = new StringBuilder();
        //            for (UnicodeProperty prop : props) {
        //                if (builder.length() != 0) {
        //                    builder.append("; ");
        //                }
        //
        // builder.append(prop.getName()).append("=").append(prop.getValue(codePoint));
        //            }
        //            return builder.toString();
        //        }
    }

    private static String getName(
            String string,
            String separator,
            boolean showDevProperties,
            boolean andCode,
            boolean plainText) {
        StringBuilder result = new StringBuilder();
        int cp;
        for (int i = 0; i < string.length(); i += UTF16.getCharCount(cp)) {
            cp = UTF16.charAt(string, i);
            if (i != 0) {
                result.append(separator);
            }
            if (andCode) {
                result.append("U+").append(com.ibm.icu.impl.Utility.hex(cp, 4)).append(' ');
            }
            final String devName =
                    showDevProperties ? getFactory().getProperty("Udev:Name").getValue(cp) : null;
            final String name = getFactory().getProperty("Name").getValue(cp);
            if (name != null) {
                result.append(name);
            } else {
                if (devName != null) {
                    if (plainText) {
                        result.append("{" + devName + "}");
                    } else {
                        result.append("<span class='changed'>" + devName + "</span>");
                    }
                } else {
                    String alias = getFactory().getProperty("Name_Alias").getValue(cp);
                    if (alias == null) {
                        alias = "no name";
                    }
                    if (plainText) {
                        result.append("(" + alias + ")");
                    } else {
                        result.append("<i>" + alias + "</i>");
                    }
                }
            }
        }
        return result.toString();
    }

    private static String getHex(
            int codePoint, boolean ucdFormat, List<String> additionalParameters) {
        String hex = com.ibm.icu.impl.Utility.hex(codePoint, 4);
        final String string =
                "<code>"
                        + ("<a target='c' href='character.jsp?a="
                                + hex
                                + additionalParameters.stream()
                                        .map(p -> "&" + p)
                                        .collect(Collectors.joining())
                                + "'>")
                        + (ucdFormat ? "" : "U+")
                        + hex
                        + "</a></code>";
        return string;
    }

    private static String getHex(
            String string, String separator, boolean ucdFormat, List<String> additionalParameters) {
        StringBuilder result = new StringBuilder();
        int cp;
        for (int i = 0; i < string.length(); i += UTF16.getCharCount(cp)) {
            if (i != 0) {
                result.append(separator);
            }
            result.append(getHex(cp = UTF16.charAt(string, i), ucdFormat, additionalParameters));
        }
        return result.toString();
    }

    //  private static void showString(String s, String separator, boolean ucdFormat, Writer out)
    // throws IOException {
    //    int cp;
    //    for (int i = 0; i < s.length(); i += UTF16.getCharCount(cp)) {
    //      if (i != 0) {
    //        out.write(separator);
    //      }
    //      showCodePoint(cp = UTF16.charAt(s, i), ucdFormat, out);
    //    }
    //  }

    static final UnicodeSet MAPPING_SET = new UnicodeSet("[:^c:]");
    static final Normalizer2 NFKCCF = Normalizer2.getNFKCCasefoldInstance();
    static final SpoofChecker sc = new SpoofChecker.Builder().build();

    static {
        Transliterator.registerInstance(
                getTransliteratorFromFile("en-IPA", "en-IPA.txt", Transliterator.FORWARD));
        Transliterator.registerInstance(
                getTransliteratorFromFile("IPA-en", "en-IPA.txt", Transliterator.REVERSE));

        Transliterator.registerInstance(
                getTransliteratorFromFile("deva-ipa", "Deva-IPA.txt", Transliterator.FORWARD));
        Transliterator.registerInstance(
                getTransliteratorFromFile("ipa-deva", "Deva-IPA.txt", Transliterator.REVERSE));

        Transform<String, String> confusable =
                new Transform<String, String>() {
                    public String transform(String source) {
                        return sc.getSkeleton(SpoofChecker.ANY_CASE, source); // doc wrong
                    }
                };
        Transliterator.registerInstance(new SimpleTransliterator("confusable", confusable));

        Transform<String, String> confusableLower =
                new Transform<String, String>() {
                    public String transform(String source) {
                        return sc.getSkeleton(0, source); // doc wrong
                    }
                };
        Transliterator.registerInstance(
                new SimpleTransliterator("confusableLower", confusableLower));

        Transform<String, String> nfkccf =
                new Transform<String, String>() {
                    public String transform(String source) {
                        return NFKCCF.normalize(source); // doc wrong
                    }
                };
        Transliterator.registerInstance(new SimpleTransliterator("NFKCCF", confusable));
    }

    public static Transliterator getTransliteratorFromFile(String ID, String file, int direction) {
        try {
            BufferedReader br = FileUtilities.openFile(UnicodeUtilities.class, file);
            StringBuffer input = new StringBuffer();
            while (true) {
                String line = br.readLine();
                if (line == null) {
                    break;
                }
                if (line.startsWith("\uFEFF")) {
                    line = line.substring(1); // remove BOM
                }
                input.append(line);
                input.append('\n');
            }
            return Transliterator.createFromRules(ID, input.toString(), direction);
        } catch (IOException e) {
            throw (IllegalArgumentException)
                    new IllegalArgumentException("Can't open transliterator file " + file)
                            .initCause(e);
        }
    }

    public static final Transliterator UNESCAPER = Transliterator.getInstance("hex-any");

    /*jsp*/
    public static String showTransform(String transform, String sample) {
        //    if (!haveCaseFold) {
        //      registerCaseFold();
        //    }
        Transliterator trans;
        try {
            trans = Transliterator.createFromRules("foo", transform, Transliterator.FORWARD);
        } catch (Exception e) {
            try {
                trans = Transliterator.getInstance(transform);
            } catch (Exception e2) {
                return "Error: " + toHTML.transform(e.getMessage() + "; " + e2.getMessage());
            }
        }

        UnicodeSet set = null;
        // see if sample is a UnicodeSet
        if (UnicodeSet.resemblesPattern(sample, 0)) {
            try {
                set = UnicodeSetUtilities.parseUnicodeSet(sample);
            } catch (Exception e) {
            }
        }
        if (set == null) {
            sample = UNESCAPER.transform(sample);
            return getLiteral(trans.transform(sample)).replace("\n", "<br>");
        }

        UnicodeSetPrettyPrinter pp =
                new UnicodeSetPrettyPrinter()
                        .setOrdering(UnicodeSetUtilities.MAIN_COLLATOR)
                        // .setSpaceComparator(Collator.getInstance(ULocale.ROOT).setStrength2(RuleBasedCollator.PRIMARY))
                        .setSpaceComparator(
                                new Comparator<String>() {
                                    public int compare(String o1, String o2) {
                                        return 1;
                                    }
                                });

        Map<String, UnicodeSet> mapping = new TreeMap<String, UnicodeSet>(pp.getOrdering());

        for (UnicodeSetIterator it = new UnicodeSetIterator(set); it.next(); ) {
            String s = it.getString();
            String mapped = trans.transform(s);
            if (!mapped.equals(s)) {
                UnicodeSet x = mapping.get(mapped);
                if (x == null) {
                    mapping.put(mapped, x = new UnicodeSet());
                }
                x.add(s);
            }
        }
        StringBuilder result = new StringBuilder();
        for (String mapped : mapping.keySet()) {
            UnicodeSet source = mapping.get(mapped);
            result.append(showCodePoint(mapped));
            result.append("\t←\t");
            if (source.size() == 1) {
                UnicodeSetIterator it = new UnicodeSetIterator(source);
                it.next();
                result.append(showCodePoint(it.getString()));
            } else {
                result.append(showCodePoint(pp.format(source)));
            }
            result.append("</br>\n");
        }
        return result.toString();
    }

    public static class StringPair implements Comparable<StringPair> {
        String first;
        String second;

        public StringPair(String first, String second) {
            this.first = first;
            this.second = second;
        }

        public int compareTo(StringPair o) {
            int result = first.compareTo(o.first);
            if (result != 0) {
                return result;
            }
            return second.compareTo(o.second);
        }
    }

    static String TRANSFORMLIST = null;

    public static String listTransforms() {
        if (TRANSFORMLIST == null) {
            StringBuilder result = new StringBuilder();
            Set<StringPair> pairs = new TreeSet<StringPair>();
            Set<String> sources =
                    append(
                            new TreeSet<String>(col),
                            (Enumeration<String>) Transliterator.getAvailableSources());
            for (String source : sources) {
                Set<String> targets =
                        append(
                                new TreeSet<String>(col),
                                (Enumeration<String>) Transliterator.getAvailableTargets(source));
                for (String target : targets) {
                    Set<String> variants =
                            append(
                                    new TreeSet<String>(col),
                                    (Enumeration<String>)
                                            Transliterator.getAvailableVariants(source, target));
                    for (String variant : variants) {
                        final String id =
                                toHTML.transform(
                                        source
                                                + "-"
                                                + target
                                                + (variant.length() == 0 ? "" : "/" + variant));
                        pairs.add(new StringPair(target, id));
                    }
                }
            }
            result.append("<hr><table><tr><th>Result</th><th>IDs</th></tr>\n");
            String last = "";
            boolean first = true;
            for (StringPair pair : pairs) {
                if (!last.equals(pair.first)) {
                    if (first) {
                        first = false;
                    } else {
                        result.append("</td></tr>\n");
                    }
                    result.append("<tr><th>" + pair.first + "</th><td>");
                }
                result.append(
                        "<a href='transform.jsp?a=" + pair.second + "'>" + pair.second + "</a>\n");
                last = pair.first;
            }
            result.append("\t\t</ul>\n\t</li>\n");
            result.append("</table>");
            TRANSFORMLIST = result.toString();
        }
        return TRANSFORMLIST;
    }

    private static <T, U extends Collection<T>> U append(U result, Enumeration<T> sources) {
        while (sources.hasMoreElements()) {
            result.add(sources.nextElement());
        }
        return result;
    }

    //  private static void registerCaseFold() {
    //    StringBuilder rules = new StringBuilder();
    //    for (UnicodeSetIterator it = new UnicodeSetIterator(MAPPING_SET); it.nextRange();) {
    //      for (int i = it.codepoint; i <= it.codepointEnd; ++i) {
    //        String s = UTF16.valueOf(i);
    //        String caseFold = UCharacter.foldCase(s, true);
    //        String lower = UCharacter.toLowerCase(Locale.ENGLISH, s);
    //        if (!caseFold.equals(lower) || i == 'Σ') {
    //          rules.append(s + ">" + caseFold + " ;\n");
    //        }
    //      }
    //    }
    //    rules.append("::Lower;");
    //    Transliterator.registerInstance(Transliterator.createFromRules("Any-CaseFold",
    // rules.toString(), Transliterator.FORWARD));
    //    haveCaseFold = true;
    //  }

    static class FilteredStringTransform implements StringTransform {
        final UnicodeSet toExclude;
        final StringTransform trans;

        public FilteredStringTransform(UnicodeSet toExclude, StringTransform trans) {
            this.toExclude = toExclude;
            this.trans = trans;
        }

        public String transform(String source) {
            StringBuilder result = new StringBuilder();
            int start = 0;
            while (start < source.length()) {
                int end = toExclude.findIn(source, start, false);
                result.append(trans.transform(source.substring(start, end)));
                if (end == source.length()) break;
                start = toExclude.findIn(source, end, true);
                result.append(source.substring(end, start));
            }
            return result.toString();
        }
    }

    public static final char JOINER = '\u200D';
    public static final UnicodeSet NON_ASCII = new UnicodeSet("[^\\u0021-\\u007E]").freeze();
    public static final UnicodeSet WHITESPACE_IGNORABLES_C =
            new UnicodeSet(
                            "["
                                    + "[:C:]"
                                    + "[:Default_Ignorable_Code_Point:]"
                                    + "[:patternwhitespace:]"
                                    + "[:whitespace:]"
                                    + "]")
                    .remove(JOINER)
                    .remove(0xFF0F)
                    .freeze();
    public static final UnicodeSet CombiningMarks = new UnicodeSet("[:M:]").freeze();
    public static final UnicodeSet NOBREAKBEFORE =
            new UnicodeSet(CombiningMarks)
                    .addAll(UnicodeSetUtilities.MODIFIERS)
                    .addAll(UnicodeSetUtilities.REGIONALS)
                    .add(JOINER)
                    .add('\uFE0F')
                    .add('\uFE0E')
                    .freeze();

    public static String getPrettySet(UnicodeSet a, boolean abbreviate, boolean escape) {
        String a_out;
        if (a.size() < 10000 && !abbreviate) {
            UnicodeSetPrettyPrinter pp =
                    new UnicodeSetPrettyPrinter()
                            .setOrdering(UnicodeSetUtilities.MAIN_COLLATOR)
                            .setSpaceComparator(COLLATOR.setStrength2(RuleBasedCollator.PRIMARY));

            if (escape) {
                pp.setToQuote(NON_ASCII);
                a_out = toHTML(pp.format(a));
            } else {
                pp.setToQuote(WHITESPACE_IGNORABLES_C);
                a_out = toHTML(pp.format(a));
                a_out = a_out.replace("\\u200D", "\u200D"); // hack to not show joiners
                a_out = a_out.replace("\\uFE0F", "\uFE0F"); // hack to not show joiners
            }
        } else {
            a.complement().complement();
            a_out = toHTML(a.toPattern(escape));
        }
        // insert spaces occasionally
        int cp;
        int oldCp = 0;
        StringBuffer out = new StringBuffer();
        int charCount = 0;
        Status status = Status.NORMAL;
        for (int i = 0; i < a_out.length(); i += UTF16.getCharCount(cp)) {
            cp = UTF16.charAt(a_out, i);
            ++charCount;
            switch (status) {
                case AFTERSLASH:
                    status = Status.NORMAL;
                    break;
                case INSTRINGAFTERSLASH:
                    status = Status.INSTRING;
                    break;
                case INSTRING:
                    if (cp == '\\') {
                        status = Status.INSTRINGAFTERSLASH;
                    } else if (cp == '}') {
                        status = Status.NORMAL;
                    }
                    break;
                case NORMAL:
                    if (cp == '\\') {
                        status = Status.AFTERSLASH;
                    } else if (cp == '{') {
                        status = Status.INSTRING;
                    } else if (cp == ' ') {
                        charCount = 0;
                    } else if (charCount > 20) {
                        // add a space, but not in x-y, or \\uXXXX
                        // TODO, don't change {...}
                        if (
                        // no break before character
                        cp < 0x80
                                || cp == '-'
                                || cp == '}'
                                || NOBREAKBEFORE.contains(cp)
                                // no break after character
                                || oldCp == '-'
                                || oldCp == '\\'
                                || oldCp == '{') {
                            // do nothing
                        } else if (cp == ' ') {
                            charCount = 0;
                        } else {
                            out.append(' ');
                            charCount = 0;
                        }
                    }
                    break;
            }
            UTF16.append(out, cp);
            oldCp = cp;
        }
        return out.toString();
    }

    enum Status {
        NORMAL,
        AFTERSLASH,
        INSTRING,
        INSTRINGAFTERSLASH
    }

    public static UnicodeSet parseSimpleSet(String setA, String[] exceptionMessage) {
        try {
            exceptionMessage[0] = null;
            //            setA = setA.replace("..U+", "-\\u");
            //            setA = setA.replace("U+", "\\u");
            return UnicodeSetUtilities.parseUnicodeSet(setA);
        } catch (Exception e) {
            exceptionMessage[0] = e.getMessage();
        }
        return null;
    }

    public static void getDifferences(
            String setA,
            String setB,
            boolean abbreviate,
            String[] abResults,
            int[] abSizes,
            String[] abLinks) {
        boolean escape = false;

        String setAr = toHTML.transliterate(UtfParameters.fixQuery(setA));
        String setBr = toHTML.transliterate(UtfParameters.fixQuery(setB));
        abLinks[0] = "list-unicodeset.jsp?a=[" + setAr + '-' + setBr + "]";
        abLinks[1] = "list-unicodeset.jsp?a=[" + setBr + '-' + setAr + "]";
        abLinks[2] = "list-unicodeset.jsp?a=[" + setAr + "%26" + setBr + "]";
        String[] aMessage = new String[1];
        String[] bMessage = new String[1];

        UnicodeSet a = UnicodeUtilities.parseSimpleSet(setA, aMessage);
        UnicodeSet b = UnicodeUtilities.parseSimpleSet(setB, bMessage);

        String a_b;
        String b_a;
        String ab;

        // try {
        // setA = MyNormalize(setA, Normalizer.NFC);
        // a = UnicodeUtilities.parseUnicodeSet(setA);
        // } catch (Exception e) {
        // a_b = e.getMessage();
        // }
        // UnicodeSet b = null;
        // try {
        // setB = MyNormalize(setB, Normalizer.NFC);
        // b = UnicodeUtilities.parseUnicodeSet(setB);
        // } catch (Exception e) {
        // b_a = e.getMessage();
        // }
        int a_bSize = 0, b_aSize = 0, abSize = 0;
        if (a == null || b == null) {
            a_b = a == null ? aMessage[0] : "error";
            b_a = b == null ? bMessage[0] : "error";
            ab = "error";
        } else {
            UnicodeSet temp = new UnicodeSet(a).removeAll(b);
            a_bSize = temp.size();
            a_b = getPrettySet(temp, abbreviate, escape);

            temp = new UnicodeSet(b).removeAll(a);
            b_aSize = temp.size();
            b_a = getPrettySet(temp, abbreviate, escape);

            temp = new UnicodeSet(a).retainAll(b);
            abSize = temp.size();
            ab = getPrettySet(temp, abbreviate, escape);
        }
        abResults[0] = a_b;
        abSizes[0] = a_bSize;
        abResults[1] = b_a;
        abSizes[1] = b_aSize;
        abResults[2] = ab;
        abSizes[2] = abSize;
    }

    static int[][] ranges = {
        {UProperty.BINARY_START, UProperty.BINARY_LIMIT},
        {UProperty.INT_START, UProperty.INT_LIMIT},
        {UProperty.DOUBLE_START, UProperty.DOUBLE_LIMIT},
        {UProperty.STRING_START, UProperty.STRING_LIMIT},
    };

    static Comparator<String> col = UnicodeSetUtilities.MAIN_COLLATOR;

    //    Collator.getInstance(ULocale.ROOT);
    //    static {
    //        ((RuleBasedCollator) col).setNumericCollation(true);
    //    }

    private static String getScriptCat(String versionPrefix, int cp) {
        String scriptCat =
                getFactory().getProperty(versionPrefix + "script").getValue(cp).replace("_", " ");
        if (scriptCat.equals("Common") || scriptCat.equals("Inherited")) {
            scriptCat =
                    getFactory().getProperty(versionPrefix + "gc").getValue(cp).replace("_", " ");
        } else {
            scriptCat += " Script";
        }
        return scriptCat;
    }

    public static void showProperties(
            int cp,
            String history,
            boolean showDevProperties,
            List<String> originalParameters,
            Appendable out)
            throws IOException {
        String text = UTF16.valueOf(cp);

        String name = getFactory().getProperty("Name").getValue(cp);
        final String devName =
                showDevProperties ? getFactory().getProperty("Udev:Name").getValue(cp) : null;
        if (name == null) {
            if (devName != null) {
                name = "<span class='changed'>" + toHTML.transliterate(devName) + "</span>";
            } else {
                name = "<i>Unknown</i>";
            }
        } else {
            name = toHTML.transliterate(name);
        }

        String scriptCat = getScriptCat("", cp);
        if (showDevProperties) {
            String devScriptCat = getScriptCat("Udev:", cp);
            if (!devScriptCat.equals(scriptCat)) {
                // The old and new script and GC will be given below; only show the new one here,
                // but highlighted.
                scriptCat = "<span class='changed'>" + devScriptCat + "</span>";
            }
        }

        String hex = com.ibm.icu.impl.Utility.hex(cp, 4);

        out.append("<div class='bigDiv'><table class='bigTable'>\n");
        String display = toHTML.transliterate(text);
        out.append(
                "<tr><td class='bigChar'>"
                        + (display.contains("<")
                                ? "\u00A0" + display + "\u00A0"
                                : "<svg width='1.16em' viewBox='0 0 116 116' overflow='visible'>"
                                        + "<path d='M   8,88 l 100,0' stroke='lightgrey'/>"
                                        + "<text y='88' x='0' textLength='116' font-size='100'>"
                                        + "\u00A0"
                                        + display
                                        + "\u00A0</text></svg>")
                        + "</td></tr>\n");
        out.append("<tr><td class='bigCode'>" + hex + "</td></tr>\n");
        out.append("<tr><td class='bigName'>" + name + "</td></tr>\n");
        out.append("<tr><td class='bigName'>" + scriptCat + "</td></tr>\n");
        StringBuilder confusableString = displayConfusables(cp);
        out.append(
                "<tr><td class='bigName'><span title='Confusable Characters'><i>confuse:</i> </span>");
        if (confusableString.length() == 0) {
            out.append("<span class='noConfusables'>none</span>");
        } else {
            out.append(confusableString.toString());
        }
        out.append("</td></tr>\n");
        out.append("</table></div>\n");

        List<String> availableNames = (List<String>) getFactory().getAvailableNames();
        TreeSet<String> sortedProps =
                Builder.with(new TreeSet<String>(col)).addAll(availableNames).get();

        String kRSUnicode = getFactory().getProperty("kRSUnicode").getValue(cp);
        boolean isUnihan = kRSUnicode != null;
        List<UcdProperty> indexedProperties =
                sortedProps.stream()
                        .map(UcdProperty::forString)
                        .filter(p -> p != null)
                        .collect(Collectors.toList());
        List<UcdProperty> ucdProperties =
                indexedProperties.stream()
                        .filter(
                                p ->
                                        p.getDerivedStatus() == DerivedPropertyStatus.Approved
                                                || p.getDerivedStatus()
                                                        == DerivedPropertyStatus.Provisional)
                        .collect(Collectors.toList());
        List<UcdProperty> nonUCDProperties =
                indexedProperties.stream()
                        .filter(p -> p.getDerivedStatus() == DerivedPropertyStatus.NonUCDProperty)
                        .collect(Collectors.toList());
        List<UcdProperty> ucdNonProperties =
                indexedProperties.stream()
                        .filter(p -> p.getDerivedStatus() == DerivedPropertyStatus.UCDNonProperty)
                        .collect(Collectors.toList());
        // Non-UCD non-properties, and things added directly in the tools.
        List<String> otherData =
                sortedProps.stream()
                        .filter(
                                p ->
                                        UcdProperty.forString(p) == null
                                                || UcdProperty.forString(p).getDerivedStatus()
                                                        == DerivedPropertyStatus.NonUCDNonProperty)
                        .collect(Collectors.toList());

        List<UcdProperty> cjkProperties =
                ucdProperties.stream()
                        .filter(p -> p.getNames().getShortName().startsWith("cjk"))
                        .collect(Collectors.toList());
        ucdProperties.removeIf(p -> p.getNames().getShortName().startsWith("cjk"));

        Age_Values age = Age_Values.forName(getFactory().getProperty("Age").getValue(cp));
        VersionInfo minVersion =
                history.equals("assigned") && age != Age_Values.Unassigned
                        ? VersionInfo.getInstance(age.getShortName())
                        : history.equals("full")
                                ? Utility.UNICODE_VERSIONS.get(Utility.UNICODE_VERSIONS.size() - 1)
                                : Settings.LAST_VERSION_INFO;
        if (minVersion.compareTo(UcdLoader.getOldestLoadedUcd()) < 0) {
            minVersion = UcdLoader.getOldestLoadedUcd();
            out.append(
                    "<p class='changed'>Still loading UCD versions before "
                            + minVersion.getVersionString(2, 4)
                            + "</p>");
        }

        VersionInfo maxVersion =
                showDevProperties ? Settings.LATEST_VERSION_INFO : Settings.LAST_VERSION_INFO;
        out.append("<table class='propTable'>");
        showProperties(
                ucdProperties.stream().map(UcdProperty::toString).collect(Collectors.toList()),
                (isUnihan ? "Non-Unihan " : "")
                        + "Normative, Informative, Contributory, and (Provisional) UCD properties for U+"
                        + hex,
                cp,
                minVersion,
                maxVersion,
                originalParameters,
                out);
        showProperties(
                nonUCDProperties.stream().map(UcdProperty::toString).collect(Collectors.toList()),
                "Non-UCD properties for U+" + hex,
                cp,
                minVersion,
                maxVersion,
                originalParameters,
                out);
        showProperties(
                ucdNonProperties.stream().map(UcdProperty::toString).collect(Collectors.toList()),
                "Other " + (isUnihan ? "non-Unihan " : "") + "UCD data for U+" + hex,
                cp,
                minVersion,
                maxVersion,
                originalParameters,
                out);
        if (isUnihan) {
            showProperties(
                    cjkProperties.stream().map(UcdProperty::toString).collect(Collectors.toList()),
                    "Unihan Normative, Informative, and (Provisional) properties for U+" + hex,
                    cp,
                    minVersion,
                    maxVersion,
                    originalParameters,
                    out);
        }
        showProperties(
                otherData,
                "Other information on U+" + hex,
                cp,
                minVersion,
                maxVersion,
                originalParameters,
                out);
        out.append("</table>\n");
    }

    private static void showProperties(
            List<String> properties,
            String title,
            int cp,
            VersionInfo minVersion,
            VersionInfo maxVersion,
            List<String> originalParameters,
            Appendable out)
            throws IOException {
        out.append("<tr><th colspan=2>" + title + "</th></tr>" + "<tr><td width='50%'>\n");
        out.append("<table width='100%'>\n");
        for (int i = 0; i < properties.size() / 2; ++i) {
            UnicodeProperty prop = getFactory().getProperty(properties.get(i));
            if (prop.getName().equals("confusable")) continue;

            showPropertyValue(
                    properties.get(i), cp, minVersion, maxVersion, originalParameters, out);
        }
        out.append("</table>\n");

        out.append("</td><td width='50%'>\n");

        out.append("<table width='100%'>\n");
        for (int i = properties.size() / 2; i < properties.size(); ++i) {
            UnicodeProperty prop = getFactory().getProperty(properties.get(i));
            if (prop.getName().equals("confusable")) continue;

            showPropertyValue(
                    properties.get(i), cp, minVersion, maxVersion, originalParameters, out);
        }
        out.append("</table>\n");
        out.append("</td></tr>\n");
    }

    private static StringBuilder displayConfusables(int codepoint) {
        StringBuilder confusableString = new StringBuilder();
        Set<String> skip = new HashSet<String>();
        String same = UTF16.valueOf(codepoint);
        String nfd = Normalizer.normalize(same, Normalizer.NFD);

        skip.add(same);
        skip.add(nfd);

        // get basic confusables
        Set<String> list = Confusables.getEquivalents(same);
        if (list != null) {
            for (String s : list) {
                if (same.equals(s)) {
                    continue;
                }
                if (confusableString.length() != 0) {
                    confusableString.append(", ");
                }
                getBoxedCharacters(s, confusableString);
                skip.add(s);
                String nfd2 = Normalizer.normalize(same, Normalizer.NFD);
                skip.add(nfd2);
            }
        }

        // Now, get the combinations
        if (nfd.codePointCount(0, nfd.length()) > 1) {
            if (confusableString.length() != 0) {
                confusableString.append(", ");
            }

            List<Confusables> combos = new ArrayList<Confusables>();
            // get all the combinations
            int cp;
            for (int i = 0; i < nfd.length(); i += Character.charCount(cp)) {
                if (i != 0) {
                    confusableString.append("+");
                }
                cp = nfd.codePointAt(i);
                Confusables currentCombos =
                        new Confusables(UTF16.valueOf(cp)).setNormalizationCheck(Normalizer.NFKC);
                combos.add(currentCombos);
                confusableString.append("<div class='char'>");
                for (String s : currentCombos) {
                    getBoxedCharacters(s, confusableString);
                }
                confusableString.append("</div>");
            }
            // now add them to the skip list
            addToSkip("", 0, combos, skip);
        }

        Confusables confusables = new Confusables(same).setNormalizationCheck(Normalizer.NFKC);
        for (String s : confusables) {
            if (skip.contains(s)) {
                continue;
            }
            String nfd2 = Normalizer.normalize(same, Normalizer.NFD);
            if (skip.contains(nfd2)) {
                continue;
            }
            if (confusableString.length() != 0) {
                confusableString.append(", ");
            }
            getBoxedCharacters(s, confusableString);
        }

        //
        //    // first, try the nfd
        //    skip.add(same);
        //    String nfd = Normalizer.normalize(same, Normalizer.NFD);
        //    // get all the confusables that are simple products
        //    int cp;
        //    for (int i = 0; i < nfd.length(); i += Character.charCount(cp)) {
        //      cp = nfd.codePointAt(i);
        //    }
        //
        //    for (String s: confusables) {
        //      if (same.equals(s)) {
        //        continue;
        //      }
        //      getBoxedCharacters(s, confusableString);
        //    }
        return confusableString;
    }

    // add recursively, for simplicity
    private static void addToSkip(
            String prefix, int i, List<Confusables> combos, Set<String> skip) {
        if (i >= combos.size()) {
            skip.add(prefix);
        } else {
            for (String s : combos.get(i)) {
                addToSkip(prefix + s, i + 1, combos, skip);
            }
        }
    }

    private static void getBoxedCharacters(String s, StringBuilder confusableString) {
        confusableString.append(
                "<div class='char' title='" + toHTML(UCharacter.getName(s, " + ")) + "'>");
        int cp;
        for (int i = 0; i < s.length(); i += Character.charCount(cp)) {
            cp = s.codePointAt(i);
            if (i != 0) {
                confusableString.append("+");
            }
            confusableString
                    .append(
                            "<a target='c' href='character.jsp?a="
                                    + Utility.hex(cp)
                                    + "'>"
                                    + "&nbsp;")
                    .append(toHTML(UTF16.valueOf(cp)))
                    .append("&nbsp;</a>");
        }
        confusableString.append("</div>");
    }

    private static void showPropertyValue(
            String propName,
            int codePoint,
            VersionInfo minVersion,
            VersionInfo maxVersion,
            List<String> originalParameters,
            Appendable out)
            throws IOException {
        var indexedProperty = UcdProperty.forString(propName);
        final boolean provisional =
                indexedProperty != null
                        && indexedProperty.getDerivedStatus() == DerivedPropertyStatus.Provisional;
        class PropertyAssignment {
            VersionInfo first;
            VersionInfo last;
            // The field `values` is null if the property does not exist in that range of versions.
            // If the property has the value null (<none>), `values` is a one-element list whose
            // sole element is null.
            ArrayList<String> values;
            boolean isDefault;
            int span;
        }
        final boolean isMultivalued = getFactory().getProperty(propName).isMultivalued();
        final boolean isStringValued =
                getFactory().getProperty(propName).isType(UnicodeProperty.STRING_MASK);
        List<PropertyAssignment> history = new ArrayList<>();
        if (getFactory().getProperty(propName)
                instanceof IndexUnicodeProperties.IndexUnicodeProperty) {
            for (int i = Utility.UNICODE_VERSIONS.size() - 1; i >= 0; --i) {
                final var version = Utility.UNICODE_VERSIONS.get(i);
                if (version.compareTo(minVersion) < 0) {
                    continue;
                }
                if (version.compareTo(maxVersion) > 0) {
                    break;
                }
                if (version.equals(VersionInfo.getInstance(13, 1, 0))) {
                    // Skip the infamous 13.1.0, which is missing most data files on account of not
                    // existing.
                    // See https://github.com/unicode-org/unicodetools/issues/100.
                    continue;
                }
                final var property = IndexUnicodeProperties.make(version).getProperty(propName);
                ArrayList<String> values;
                if (property.isTrivial() && !property.getName().equals("ISO_Comment")) {
                    values = null;
                } else {
                    values = new ArrayList<>();
                    property.getValues(codePoint).forEach(values::add);
                }
                PropertyAssignment lastAssignment =
                        history.isEmpty() ? null : history.get(history.size() - 1);
                if (lastAssignment == null || (!Objects.equals(values, lastAssignment.values))) {
                    PropertyAssignment assignment = new PropertyAssignment();
                    assignment.first = version;
                    assignment.last = version;
                    assignment.values = values;
                    assignment.isDefault = property.isDefault(codePoint);
                    assignment.span = 1;
                    history.add(assignment);
                } else {
                    lastAssignment.last = version;
                    ++lastAssignment.span;
                }
            }
        } else {
            var current = new PropertyAssignment();
            current.first = Settings.LAST_VERSION_INFO;
            current.last = Settings.LAST_VERSION_INFO;
            current.values = new ArrayList<>();
            getFactory().getProperty(propName).getValues(codePoint).forEach(current.values::add);
            history.add(current);
        }
        if (history.get(0).values != null || history.size() > 1) {
            out.append(
                    "<tr><th width='50%'>"
                            + (provisional ? "(" + propName + ")" : propName)
                            + "</th>");
            for (PropertyAssignment assignment : history) {
                String first =
                        assignment.first.getVersionString(2, 4)
                                + (assignment.first == Settings.LATEST_VERSION_INFO
                                        ? Settings.latestVersionPhase.toString()
                                        : "");
                String last =
                        assignment.last.getVersionString(2, 4)
                                + (assignment.last == Settings.LATEST_VERSION_INFO
                                        ? Settings.latestVersionPhase.toString()
                                        : "");
                boolean isCurrent =
                        assignment.first.compareTo(Settings.LAST_VERSION_INFO) <= 0
                                && Settings.LAST_VERSION_INFO.compareTo(assignment.last) <= 0;
                boolean showVersion =
                        indexedProperty != null
                                && (minVersion.compareTo(Settings.LAST_VERSION_INFO) < 0
                                        || history.size() > 1);
                boolean isSingleVersion = assignment.first == assignment.last;
                boolean isNew = assignment.first == Settings.LATEST_VERSION_INFO;
                String versionRange =
                        (showVersion ? (isSingleVersion ? first : first + ".." + last) + ": " : "");
                List<String> htmlValues =
                        assignment.values == null
                                ? null
                                : assignment.values.stream()
                                        .map(
                                                v ->
                                                        v == null
                                                                ? "<i>null</i>"
                                                                : toHTML.transliterate(v))
                                        .collect(Collectors.toList());
                String tdClass = "";
                if (assignment.values == null) {
                    tdClass = "class='nonexistent'";
                } else if (assignment.isDefault) {
                    tdClass = "class='default'";
                }
                String htmlEscapedPropertyPredicate = null;
                if (!isMultivalued && htmlValues != null) {
                    // The HTML value can contain a < even if it is not <i>null</i>, in the case of
                    // control characters.  Since we do not yet support escapes in property
                    // predicates, just don’t give a link in that case.
                    if (!htmlValues.get(0).contains("<")) {
                        htmlEscapedPropertyPredicate = htmlValues.get(0);
                    } else if (assignment.values.get(0) == null) {
                        htmlEscapedPropertyPredicate = "@none@";
                    }
                }
                final String href =
                        htmlEscapedPropertyPredicate == null
                                ? null
                                : "list-unicodeset.jsp?a=[:"
                                        + (isCurrent ? "" : "U" + last + ":")
                                        + propName
                                        + "="
                                        + htmlEscapedPropertyPredicate
                                        + ":]";
                final String aTag = href == null ? null : "<a target='u' href='" + href + "'>";
                Stream<String> displayedValues = null;
                if (assignment.values != null) {
                    if (isStringValued && assignment.values.get(0) != null) {
                        displayedValues =
                                IntStream.range(0, assignment.values.size())
                                        .mapToObj(
                                                i ->
                                                        (aTag == null ? "" : aTag)
                                                                + htmlValues.get(i)
                                                                + (aTag == null ? "" : "</a>")
                                                                + "&nbsp;&lt;"
                                                                + getHex(
                                                                        assignment.values.get(i),
                                                                        ", ",
                                                                        false,
                                                                        originalParameters)
                                                                + "&gt;");
                    } else {
                        displayedValues =
                                htmlValues.stream().map(x -> aTag == null ? x : aTag + x + "</a>");
                    }
                }
                out.append(
                        "<td "
                                + tdClass
                                + " colspan="
                                + assignment.span
                                + ">"
                                + (displayedValues != null
                                        ? (isNew ? "<span class='changed'>" : "")
                                                + versionRange
                                                + (isStringValued && href != null ? "</a>" : "")
                                                + displayedValues.collect(
                                                        Collectors.joining("<wbr>|&#x2060;"))
                                                + (isNew ? "</span>" : "")
                                        : "")
                                + "</td>");
            }
            out.append("</tr>");
        }
    }

    /*jsp*/
    public static void showPropsTable(Appendable out, String propForValues, String myLink)
            throws IOException {
        //        ((RuleBasedCollator)col).setNumericCollation(true);
        Map<String, Map<String, String>> alpha = new TreeMap<String, Map<String, String>>(col);
        Map<String, String> longToShort = new HashMap<String, String>();

        Set<String> showLink = new HashSet<String>();

        TablePrinter tablePrinter =
                new TablePrinter()
                        .setTableAttributes("style='border-collapse: collapse' border='1'")
                        .addColumn("Category")
                        .setSpanRows(true)
                        .setBreakSpans(true)
                        .setCellAttributes("class='propCategory'")
                        .setSortPriority(0)
                        .addColumn("Datatype")
                        .setSpanRows(true)
                        .setCellAttributes("class='propDatatype'")
                        .setSortPriority(1)
                        .addColumn("Source")
                        .setSpanRows(true)
                        .setCellAttributes("class='propSource'")
                        .setSortPriority(2)
                        .addColumn("Property")
                        .setSpanRows(false)
                        .setCellAttributes("class='propTitle'")
                        //        .addColumn("Abbr.
                        // Prop").setSpanRows(false).setCellAttributes("class='propTitle'")
                        .addColumn("Values")
                        .setSpanRows(false)
                        .setCellAttributes("class='propValues'");
        // tablePrinter.addRows(data);
        // tablePrinter.addRow().addCell("Foo").addCell(1.5d).addCell(99).finishRow();

        // out.append("<table>\n");
        //    out.append("<tr><th>Source</th>\n")
        //    .append("<th>Category</th>\n")
        //    .append("<th>Datatype</th>\n")
        //    .append("<th>Property</th>\n")
        //    .append("<th>Values</th></tr>\n");

        // for (String propName : Builder.with(new
        // TreeSet<String>(col)).addAll((List<String>)getFactory().getAvailableNames()).get()) {
        Set<String> missing = new TreeSet<String>(COLLATOR);
        missing.addAll(getFactory().getAvailableNames());
        for (R4<String, String, String, String> propData :
                PropertyMetadata.getCategoryDatatypeSourceProperty()) {
            String propName = propData.get3();
            UnicodeProperty prop = getFactory().getProperty(propName);
            if (prop == null) continue;
            missing.remove(prop.getName());

            String shortName;
            try {
                shortName = prop.getFirstNameAlias();
            } catch (Exception e) {
                throw new IllegalArgumentException(propData.toString(), e);
            }
            String propHtml = toHTML.transform(propName);
            String shortHtml =
                    shortName == null || shortName.equalsIgnoreCase(propName)
                            ? propHtml
                            : toHTML(shortName);
            //            String title = shortName == null || shortName.equals(propName) ? "" : "
            // title='" + shortHtml + "'";
            //            String propHtml = toHTML.transform(propName +
            // (shortName.equalsIgnoreCase(propName) ? "" : " (" + shortName + ")"));
            String propInfo = propHtml; // "<a name='" + propHtml + "'>" + propHtml + "</a>";
            StringBuilder propValues = new StringBuilder();
            String dataType = propData.get1();
            if (propName.equals(propForValues)
                    || (dataType.equals("Binary") || dataType.equals("Enumerated"))
                            && prop.getAvailableValues().size() < 10) {
                getHtmlPropValues(prop, propHtml, shortHtml, propValues);
            } else {
                propValues.append(
                        "<a href='"
                                + myLink
                                + "?a="
                                + propName
                                + "#"
                                + propName
                                + "'>Show Values</a>");
            }
            tablePrinter
                    .addRow()
                    .addCell(propData.get0())
                    .addCell(dataType)
                    .addCell(propData.get2())
                    .addCell(propInfo)
                    // .addCell(shortHtml)
                    .addCell(propValues.toString())
                    .finishRow();
            // out.append("</td></tr>\n");
        }
        for (String name : missing) {
            String propHtml = toHTML.transform(name);

            tablePrinter
                    .addRow()
                    .addCell("Z-Other")
                    .addCell("Other")
                    .addCell("Other")
                    .addCell(propHtml)
                    // .addCell(shortHtml)
                    .addCell("Other")
                    .finishRow();
        }
        // out.append("</table>\n");
        out.append(tablePrinter.toTable());
    }

    private static void getHtmlPropValues(
            UnicodeProperty prop, String propHtml, String shortPropHtml, StringBuilder propValues) {
        List<String> availableValues = (List<String>) prop.getAvailableValues();
        TreeSet<String> sortedList =
                Builder.with(new TreeSet<String>(col)).addAll(availableValues).get();
        int count = 255;
        int lastFirstChar = 0;
        for (String valueName : sortedList) {
            if (--count < 0) {
                propValues.append("\n<i>too many values to show</i>");
                break;
            }
            int firstChar = valueName.length() == 0 ? -1 : valueName.codePointAt(0);
            if (lastFirstChar != 0) {
                if (lastFirstChar != firstChar) {
                    propValues.append(",<br>\n");
                } else {
                    propValues.append(", ");
                }
            }
            lastFirstChar = firstChar;
            String valueHtml = toHTML.transform(valueName);
            if (valueName.startsWith("<") && valueName.endsWith(">")) {
                propValues.append(valueHtml);
            } else {
                String shortValueHtml = toHTML(prop.getFirstValueAlias(valueName));
                propValues.append(getPropLink(propHtml, shortPropHtml, valueHtml, shortValueHtml));
            }
        }
    }

    private static String getPropLink(
            String propHtml, String shortPropHtml, String valueHtml, String shortValueHtml) {
        String propValue = valueHtml;
        final String propExp = propHtml + "=" + propValue;
        // String title = shortName == null ? "" : " title='" + toHTML(shortName) + "'";
        String result =
                "<a target='u' href='list-unicodeset.jsp?a=[:"
                        + propExp
                        + ":]'"
                        + ">"
                        + valueHtml
                        + "</a>";
        if (propHtml.isEmpty()) {
            shortPropHtml = propHtml;
        }
        if (shortValueHtml == null) {
            shortValueHtml = valueHtml;
        }
        if (!propHtml.equalsIgnoreCase(shortPropHtml)
                || !shortValueHtml.equalsIgnoreCase(valueHtml)) {
            String shortPropExp =
                    propValue.equals("Yes")
                            ? shortPropHtml
                            : propValue.equals("No")
                                    ? "^" + shortPropHtml
                                    : shortPropHtml + "=" + shortValueHtml;
            result +=
                    "\u00A0(<a target='u' href='list-unicodeset.jsp?a=[:"
                            + shortPropExp
                            + ":]'"
                            + ">"
                            + shortValueHtml
                            + "</a>)";
        }
        return result;
    }

    // static IdnaLabelTester tester = null;
    static String removals =
            new UnicodeSet("[\u1806[:di:]-[:cn:]]").complement().complement().toPattern(false);
    static Matcher rem = Pattern.compile(removals).matcher("");

    // TODO use UnicodeRegex

    //  static IdnaLabelTester getIdna2008Tester() {
    //    if (tester == null) {
    //      try {
    //        URL path = UnicodeUtilities.class.getResource("idnaContextRules.txt");
    //        String externalForm = path.toExternalForm();
    //        if (externalForm.startsWith("file:")) {
    //          externalForm = externalForm.substring(5);
    //        }
    //        tester = new IdnaLabelTester(externalForm);
    //      } catch (IOException e) {
    //        throw new IllegalArgumentException(e);
    //      }
    //    }
    //    return tester;
    //  }

    static void addBlank(StringBuilder resultLines) {
        resultLines.append("<tr><td colSpan='5'>&nbsp;</td></tr>\n");
    }

    static void addCell(
            StringBuilder resultLines,
            Transliterator hex,
            String tr46,
            String attributes,
            String confusableChoice) {
        if (tr46 == null) {
            resultLines.append("<td " + attributes + "><i>fails</i></td>\n");
        } else {
            String escaped = showEscaped(tr46);
            String linkStart = "", linkEnd = "";
            if (confusableChoice != null) {
                linkStart =
                        "<a target='confusables' href='confusables.jsp?&r="
                                + confusableChoice
                                + "&a="
                                + toHTML.transform(tr46)
                                + "'>";
                linkEnd = "</a>";
            }
            resultLines
                    .append("<td " + attributes + (" title='" + hex.transform(tr46) + "'") + ">")
                    .append(linkStart)
                    .append(escaped)
                    .append(linkEnd)
                    .append("</td>\n");
        }
    }

    public static final UnicodeSet TO_QUOTE =
            new UnicodeSet("[[:z:][:me:][:mn:][:di:][:c:]-[\u0020]]");

    static final Transliterator ESCAPER =
            Transliterator.createFromRules(
                    "escaper",
                    "("
                            + TO_QUOTE
                            + ") > '<span class=\"q\">'&any-hex($1)'</span>';"
                            + HTML_RULES_CONTROLS,
                    Transliterator.FORWARD);

    public static final UnicodeSet SYMBOL = new UnicodeSet("[:s:]").freeze();
    public static final UnicodeSet PUNCTUATION = new UnicodeSet("[:p:]").freeze();

    private static String showEscaped(String line) {
        String toShow = toHTML.transform(line);
        String escaped = ESCAPER.transform(line);
        if (!escaped.equals(toShow)) {
            toShow += "<br><span class='esc'>" + escaped + "</span>";
        }
        return toShow;
    }

    public static String showBidi(String str, int baseDirection, boolean asciiHack) {
        // warning, only BMP for now
        final StringWriter stringWriter = new StringWriter();
        PrintWriter writer = new PrintWriter(stringWriter);

        BidiCharMap bidiCharMap = new BidiCharMap(asciiHack);

        String[] parts = str.split("\\r\\n?|\\n");
        for (int i = 0; i < parts.length; ++i) {
            writer.println("<h3>Paragraph " + (i + 1) + "</h3>");
            if (parts[i] == null || parts[i].length() == 0) {
                continue;
            }
            showBidiLine(parts[i], baseDirection, writer, bidiCharMap);
        }

        if (asciiHack) {
            writer.println("<h3>ASCII Hack</h3>");
            writer.println(
                    "<p>For testing the UBA with only ASCII characters, the following property values are used (<,> are RLM and LRM):</p>");
            writer.println("<table>");
            for (byte i = 0; i < BidiReference.typenames.length; ++i) {
                final UnicodeSet modifiedClass = bidiCharMap.getAsciiHack(i);
                writer.println(
                        "<tr><th>"
                                + BidiReference.getHtmlTypename(i)
                                + "</th><td>"
                                + getList(modifiedClass)
                                + "</td></tr>");
            }
            writer.println("</table>");
        }

        writer.flush();
        return stringWriter.toString();
    }

    private static String getList(final UnicodeSet uset) {
        StringBuffer codePointString = new StringBuffer();
        for (UnicodeSetIterator it = new UnicodeSetIterator(uset); it.next(); ) {
            if (codePointString.length() != 0) {
                codePointString.append(" ");
            }
            final String literal =
                    it.codepoint <= 0x20
                            ? "\u00AB"
                                    + getLiteral(UCharacter.getExtendedName(it.codepoint))
                                    + "\u00BB"
                            : getLiteral(it.codepoint);
            codePointString.append(literal);
        }
        return codePointString.toString();
    }

    private static void showBidiLine(
            String str, int baseDirection, PrintWriter writer, BidiCharMap bidiCharMap) {
        byte[] codes = new byte[str.length()];
        for (int i = 0; i < str.length(); ++i) {
            codes[i] = bidiCharMap.getBidiClass(str.charAt(i));
        }
        int[] linebreaks = new int[1];
        linebreaks[0] = str.length();

        BidiReference bidi = new BidiReference(codes, (byte) baseDirection);
        int[] reorder = bidi.getReordering(new int[] {codes.length});
        byte[] levels = bidi.getLevels(linebreaks);

        writer.println("<table><tr><th>Base Level</th>");
        final byte baseLevel = bidi.getBaseLevel();
        writer.println(
                "<td>"
                        + baseLevel
                        + " = "
                        + (baseLevel == 0 ? "LTR" : "RTL")
                        + "</td><td>"
                        + (baseDirection >= 0 ? "explicit" : "heuristic")
                        + "</td>");
        writer.println("</tr></table>");

        // output original text
        writer.println("<h3>Source</h3>");
        writer.println("<table><tr><th>Memory Position</th>");
        for (int i = 0; i < str.length(); ++i) {
            writer.println("<td class='bcell'>" + i + "</td>");
        }
        writer.println("</tr><tr><th>Character</th>");
        for (int i = 0; i < str.length(); ++i) {
            final String s = str.substring(i, i + 1);
            String title = toHTML.transform(getName(s, "", false, true, true));
            writer.println(
                    "<td class='bccell' title='"
                            + title
                            + "'> "
                            + getLiteral(getBidiChar(str, i, codes[i]))
                            + " </td>");
        }
        writer.println("</tr><tr><th>Bidi Class</th>");
        for (int i = 0; i < str.length(); ++i) {
            writer.println(
                    "<td class='bcell'><tt>"
                            + BidiReference.getHtmlTypename(codes[i])
                            + "</tt></td>");
        }
        writer.println("</tr><tr><th>Rules Applied</th>");
        for (int i = 0; i < str.length(); ++i) {
            writer.println(
                    "<td class='bcell'><tt>"
                            + bidi.getChanges(i).replace("\n", "<br>")
                            + "</tt></td>");
        }
        writer.println("</tr><tr><th>Resulting Level</th>");
        for (int i = 0; i < str.length(); ++i) {
            writer.println("<td class='bcell'><tt>" + showLevel(levels[i]) + "</tt></td>");
        }
        writer.println("</tr></table>");

        // output visually ordered text
        writer.println("<h3>Reordered</h3>");
        writer.println("<table><th>Display Position</th>");
        for (int k = 0; k < str.length(); ++k) {
            final int i = reorder[k];
            final String bidiChar = getBidiChar(str, i, codes[i]);
            String td = bidiChar.length() == 0 ? "<td class='bxcell'>" : "<td class='bcell'>";
            writer.println(td + k + "</td>");
        }
        writer.println("</tr><tr><th>Memory Position</th>");
        for (int k = 0; k < str.length(); ++k) {
            final int i = reorder[k];
            final String bidiChar = getBidiChar(str, i, codes[i]);
            String td = bidiChar.length() == 0 ? "<td class='bxcell'>" : "<td class='bcell'>";
            writer.println(td + i + "</td>");
        }
        writer.println("</tr><tr><th>Character</th>");
        for (int k = 0; k < str.length(); ++k) {
            final int i = reorder[k];
            final String bidiChar = getBidiChar(str, i, codes[i]);
            String title =
                    bidiChar.length() == 0
                            ? "deleted"
                            : toHTML.transform(getName(bidiChar, "", false, true, true));
            String td = bidiChar.length() == 0 ? "bxcell" : "bccell";
            writer.println(
                    "<td class='"
                            + td
                            + "' title='"
                            + title
                            + "'>"
                            + " "
                            + getLiteral(bidiChar)
                            + "</td>");
        }
        writer.println("</tr></table>");
    }

    private static String getBidiChar(String str, int i, byte b) {
        if (b == BidiReference.PDF
                || b == BidiReference.RLE
                || b == BidiReference.LRE
                || b == BidiReference.LRO
                || b == BidiReference.RLO
                || b == BidiReference.BN) {
            return "";
        }
        String substring = str.substring(i, i + 1);
        if ((substring.equals("<") || substring.equals(">"))
                && (b == BidiReference.L || b == BidiReference.R)) {
            return "";
        }
        return substring;
    }

    private static String showLevel(int level) {
        StringBuffer result = new StringBuffer();
        for (int i = 0; i < level; ++i) {
            result.append("<br>");
        }
        result.append("L").append(level);
        return result.toString();
    }

    public static String testIdnaLines(String lines, String filter) {
        Transliterator hex = Transliterator.getInstance("any-hex");
        try {

            lines = UnicodeJsp.UNESCAPER.transform(lines.trim());
            StringBuilder resultLines = new StringBuilder();
            // UnicodeUtilities.getIdna2008Tester();

            Predicate<String> verifier2008 =
                    new Predicate<String>() {
                        public boolean is(String item) {
                            return Idna2008.SINGLETON.isValid(item);
                        }
                    };

            resultLines.append("<table>\n");
            resultLines.append(
                    "<th></th><th class='cn'>Input</th><th class='cn'>IDNA2003</th><th class='cn'>UTS46</th><th class='cn'>IDNA2008</th>\n");

            boolean first = true;
            boolean[] errorOut = new boolean[1];

            for (String line : lines.split("\\s+")) {
                if (first) {
                    first = false;
                } else {
                    addBlank(resultLines);
                }

                String rawPunycode =
                        UnicodeUtilities.processLabels(
                                line,
                                IdnaTypes.DOTS,
                                true,
                                new Predicate() {
                                    public boolean is(Object item) {
                                        return true;
                                    }
                                });

                //        String tr46 = UnicodeUtilities.processLabels(tr46back,
                // UnicodeUtilities.DOTS, true, new Predicate<String>() {
                //          public boolean is(String item) {
                //            return Uts46.SINGLETON.transform(item).indexOf('\uFFFD') < 0; //
                // Uts46.SINGLETON.Uts46Chars.containsAll(item);
                //          }
                //        });
                //        String tr46display = Uts46.SINGLETON.toUnicode(line, errorOut);
                //        tr46display = UnicodeUtilities.processLabels(tr46display,
                // UnicodeUtilities.DOTS, false, new Predicate<String>() {
                //          public boolean is(String item) {
                //            return Uts46.SINGLETON.toUnicode(item).indexOf('\uFFFD') < 0; //
                // Uts46.SINGLETON.Uts46Chars.containsAll(item);
                //            //return Uts46.SINGLETON.Uts46CharsDisplay.containsAll(item);
                //          }
                //        });

                // first lines
                resultLines.append("<tr>");
                resultLines.append("<th>Display</th>");
                addCell(resultLines, hex, line, "class='cn ltgreen'", "None");
                String idna2003unic = Idna2003.SINGLETON.toUnicode(line, errorOut, true);
                addCell(
                        resultLines,
                        hex,
                        idna2003unic,
                        getIdnaClass("cn i2003", errorOut[0]),
                        "IDNA2003");

                String uts46unic = Uts46.SINGLETON.toUnicode(line, errorOut, true);
                addCell(
                        resultLines,
                        hex,
                        uts46unic,
                        getIdnaClass("cn i46", errorOut[0]),
                        "UTS46%2BUTS39");

                String idna2008unic =
                        UnicodeUtilities.processLabels(line, IdnaTypes.DOT, false, verifier2008);
                addCell(
                        resultLines,
                        hex,
                        idna2008unic,
                        getIdnaClass("cn i2008", idna2008unic.contains("\uFFFD")),
                        "IDNA2003");
                resultLines.append("<tr></tr>");

                resultLines.append("<th class='mono'>Punycode</th>");
                addCell(resultLines, hex, rawPunycode, "class='cn ltgreen mono'", null);
                String idna2003puny = Idna2003.SINGLETON.toPunyCode(line, errorOut);
                addCell(
                        resultLines,
                        hex,
                        idna2003puny,
                        getIdnaClass("cn mono i2003", errorOut[0]),
                        null);

                String uts46puny = Uts46.SINGLETON.toPunyCode(line, errorOut);
                addCell(
                        resultLines,
                        hex,
                        uts46puny,
                        getIdnaClass("cn mono i46", errorOut[0]),
                        null);

                String idna2008puny =
                        UnicodeUtilities.processLabels(line, IdnaTypes.DOT, true, verifier2008);
                addCell(
                        resultLines,
                        hex,
                        idna2008puny,
                        getIdnaClass("cn mono i2008", idna2008puny.contains("\uFFFD")),
                        null);

                //        if (result == null) {
                //          resultLines.append("<td class='c'>\u00A0</td><td
                // class='c'>\u00A0</td>");
                //        } else {
                //          resultLines.append("<td class='c'>")
                //
                // .append(toHTML.transform(IdnaLabelTester.ESCAPER.transform(normalized.substring(0, result.position)))
                //                  + "<span class='x'>\u2639</span>" +
                // toHTML.transform(IdnaLabelTester.ESCAPER.transform(normalized.substring(result.position)))
                //                  + "</td><td>" + result.title
                //                  //+ "</td><td class='c'>" + result.ruleLine
                //                  + "</td>");
                //        }
                resultLines.append("</tr>\n");
            }

            resultLines.append("</table>\n");
            return resultLines.toString();
        } catch (Exception e) {
            return toHTML.transform(e.getMessage());
        }
    }

    private static String getIdnaClass(String classItems, boolean error) {
        return "class='" + classItems + (error ? " error" : "") + "'";
    }

    static String processLabels(
            String inputLabels, Pattern dotPattern, boolean punycode, Predicate<String> verifier) {
        StringBuilder result = new StringBuilder();
        for (String label : dotPattern.split(inputLabels)) {
            if (result.length() != 0) {
                result.append('.');
            }
            try {
                if (!verifier.is(label)) {
                    throw new IllegalArgumentException();
                }
                if (!punycode || IdnaTypes.ASCII.containsAll(label)) {
                    result.append(label);
                } else {
                    StringBuffer puny = Punycode.encode(new StringBuffer(label), null);
                    if (puny.length() == 0) {
                        throw new IllegalArgumentException();
                    }
                    result.append("xn--").append(puny);
                }
            } catch (Exception e) {
                result.append('\uFFFD');
            }
        }
        return result.toString();
    }
}

/*
 * <% http://www.devshed.com/c/a/Java/Developing-JavaServer-Pages/ Enumeration
 * parameterNames = request.getParameterNames(); while
 * (parameterNames.hasMoreElements()){ String parameterName = (String)
 * parameterNames.nextElement(); String parameterValue =
 * request.getParameter(parameterName); %> <%= parameterName %> has value <%=
 * parameterValue %>. <br> <% } %>
 */
