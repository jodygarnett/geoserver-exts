package org.geotools.ysld.parse;

import org.geotools.filter.function.FilterFunction_strConcat;
import org.geotools.filter.function.string.ConcatenateFunction;
import org.geotools.renderer.style.ExpressionExtractor;
import org.geotools.styling.AnchorPoint;
import org.geotools.styling.Displacement;
import org.geotools.ysld.Colors;
import org.geotools.ysld.Tuple;
import org.opengis.filter.expression.Expression;
import org.opengis.filter.expression.Function;
import org.opengis.filter.expression.Literal;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * Parsing utilities
 */
public class Util {

    /**
     * Pattern to catch attribute expressions.
     */
    static final Pattern ATTRIBUTE_PATTERN = Pattern.compile("\\[.+\\]");

    /**
     * Pattern to catch url style well known names
     */
    static final Pattern WELLKNOWNNAME_PATTERN = Pattern.compile("\\w+://.+");

    /**
     * Pattern to catch 6 digit hex colors. 
     */
    static final Pattern COLOR_PATTERN = Pattern.compile("#\\p{XDigit}{6}");

    /**
     * Parses an expression from its string representation.
     */
    public static Expression expression(String value, Factory factory) {
        return expression(value, false, factory);
    }
    private static void collectExpressions (List<Expression> list, Expression expr) {
        if(expr==null) return;
        if (expr instanceof ConcatenateFunction || expr instanceof FilterFunction_strConcat) {
            for(Expression param: ((Function)expr).getParameters()) {
                collectExpressions(list, param);
            }
            return;
        } else {
            if(expr instanceof Literal) {
                Object value = ((Literal) expr).getValue();
                if(value==null) return;
                if(value instanceof String && ((String) value).isEmpty()) return;
            }
            list.add(expr);
        }
    }
    
    
    public static Expression unwrapConcatenates(Expression expr,
            Factory factory) {
        List<Expression> list = splitConcatenates(expr);
        if(list.isEmpty()) {
            return factory.filter.literal(null);
        } else if(list.size()==1) {
            return list.get(0);
        } else {
            return factory.filter.function("Concatenate", list.toArray(new Expression[]{}));
        }
    }
    
    public static List<Expression> splitConcatenates(Expression expr) {
        List<Expression> list = new ArrayList<>();
        collectExpressions(list, expr);
        return list;
    }
    /**
     * Parses an expression from its string representation.
     * <p>
     * The <tt>safe</tt> parameter when set to true will cause null to be returned
     * when the string can not be parsed as a ECQL expression. When false it will
     * result in an exception thrown back.
     * </p>
     */
    public static Expression expression(String value, boolean safe, Factory factory) {
        
        Expression expr = ExpressionExtractor.extractCqlExpressions(value);
        
        
        expr = unwrapConcatenates(expr, factory);
        //Expression expr = ECQL.toExpression(value, factory.filter);
        /*if (expr instanceof PropertyName && !ATTRIBUTE_PATTERN.matcher(value).matches()) {
            // treat as literal
            return factory.filter.literal(((PropertyName) expr).getPropertyName());
        }*/
        return expr;
    }
    

    /**
     * Parses an anchor tuple.
     */
    public static AnchorPoint anchor(String value, Factory factory) {
        Tuple t = null;
        try {
            t = Tuple.of(2).parse(value);
        }
        catch(IllegalArgumentException e) {
            throw new IllegalArgumentException(String.format("Bad anchor: '%s', must be of form (<x>,<y>)", value), e);
        }

        Expression x = t.at(0) != null ? expression(t.strAt(0), factory) : factory.filter.literal(0);
        Expression y = t.at(1) != null ? expression(t.strAt(1), factory) : factory.filter.literal(0);
        return factory.style.createAnchorPoint(x, y);
    }

    /**
     * Parses an displacement tuple.
     */
    public static Displacement displacement(String value, Factory factory) {
        Tuple t = null;
        try {
            t = Tuple.of(2).parse(value);
        }
        catch(IllegalArgumentException e) {
            throw new IllegalArgumentException(String.format("Bad displacement: '%s', must be of form (<x>,<y>)", value), e);
        }

        Expression x = t.at(0) != null ? expression(t.strAt(0), factory) : factory.filter.literal(0);
        Expression y = t.at(1) != null ? expression(t.strAt(1), factory) : factory.filter.literal(0);
        return factory.style.createDisplacement(x, y);
    }

    static final Pattern HEX_PATTERN = Pattern.compile("\\s*(?:(?:0x)|#)?([A-Fa-f0-9]{3}|[A-Fa-f0-9]{6})\\s*");

    static final Pattern RGB_PATTERN = Pattern.compile(
            "\\s*rgb\\s*\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*\\)\\s*", Pattern.CASE_INSENSITIVE);


    /**
     * Parses a color from string representation.
     */
    public static Expression color(Object value, Factory factory) {
        Color color = null;
        if(value instanceof String) {
            Matcher m = HEX_PATTERN.matcher((String)value);
            if (m.matches()) {
                color = parseColorAsHex(m);
            }
            if (color == null) {
                m = RGB_PATTERN.matcher((String)value);
                if (m.matches()) {
                    color = parseColorAsRGB(m);
                }
            }
            if (color == null) {
                color = Colors.get((String)value);
            }
        } else if(value instanceof Integer) {
            color = new Color((int)value);
        }
        
        if (value!=null) value = value.toString();
        
        return color != null ? factory.filter.literal(color) : expression((String)value, factory);
    }

    static Color parseColorAsHex(Matcher m) {
        String hex = m.group(1);
        if (hex.length() == 3) {
            hex += hex;
        }

        return new Color(Integer.parseInt(hex.substring(0,2), 16),
                Integer.parseInt(hex.substring(2,4), 16), Integer.parseInt(hex.substring(4,6), 16));
    }

    static Color parseColorAsRGB(Matcher m) {
        return new Color(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)));
    }

    /**
     * Parses a float array from a space delimited list.
     */
    public static float[] floatArray(String value) {
        List<Float> list = new ArrayList<Float>();
        for (String str : value.split(" ")) {
            list.add(Float.parseFloat(str));
        }

        float[] array = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i).floatValue();
        }

        return array;
    }
    
    /**
     * Returns the first non-null parameter or null.
     */
    @SafeVarargs
    @Nullable public static <T> T defaultForNull(@Nullable T... options) {
        for (T o: options) {
            if(o!=null) return o;
        }
        return null;
    }
    
    /**
     * Returns the first non-null parameter or throws NullPointerException.
     */
    @SafeVarargs
    public static <T> T forceDefaultForNull(@Nullable T... options) {
        for (T o: options) {
            if(o!=null) return o;
        }
        throw new NullPointerException();
    }

    public static @Nullable ZoomContext getNamedZoomContext(String name, List<ZoomContextFinder> zCtxtFinders){
        for(ZoomContextFinder finder: zCtxtFinders) {
            ZoomContext found = finder.get(name);
            if(found!=null) {
                return found;
            }
        }
        return WellKnownZoomContextFinder.getInstance().get(name);
    }
    
    public static String removeExpressionBrackets(String s) {
        if(s.startsWith("${") && s.endsWith("}")) {
            return s.substring(2, s.length()-1);
        }
        return s;
    }
    public static Object makeNumberIfPossible(String str) {
        if(str==null) return null;
        
        try {
            return Long.parseLong(str);
        }
        catch(NumberFormatException e1) {
            try {
                return Double.parseDouble(str);
            }
            catch(NumberFormatException e2) {
                if ("true".equalsIgnoreCase(str) || "false".equalsIgnoreCase(str)) {
                    return Boolean.parseBoolean(str);
                }
            }
        }
        return str;
    }
    public static String serializeColor(Color c) {
        return String.format("0x%06X", c.getRGB() & 0x00_FF_FF_FF);
    }
}
