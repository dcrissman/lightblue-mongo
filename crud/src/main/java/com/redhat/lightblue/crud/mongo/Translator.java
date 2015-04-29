/*
 Copyright 2013 Red Hat, Inc. and/or its affiliates.

 This file is part of lightblue.

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.redhat.lightblue.crud.mongo;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.redhat.lightblue.crud.MetadataResolver;
import com.redhat.lightblue.metadata.ArrayElement;
import com.redhat.lightblue.metadata.ArrayField;
import com.redhat.lightblue.metadata.EntityMetadata;
import com.redhat.lightblue.metadata.FieldCursor;
import com.redhat.lightblue.metadata.FieldTreeNode;
import com.redhat.lightblue.metadata.ObjectArrayElement;
import com.redhat.lightblue.metadata.ObjectField;
import com.redhat.lightblue.metadata.ReferenceField;
import com.redhat.lightblue.metadata.SimpleArrayElement;
import com.redhat.lightblue.metadata.SimpleField;
import com.redhat.lightblue.metadata.Type;
import com.redhat.lightblue.query.ArrayContainsExpression;
import com.redhat.lightblue.query.ArrayMatchExpression;
import com.redhat.lightblue.query.ArrayUpdateExpression;
import com.redhat.lightblue.query.BinaryComparisonOperator;
import com.redhat.lightblue.query.CompositeSortKey;
import com.redhat.lightblue.query.FieldAndRValue;
import com.redhat.lightblue.query.FieldComparisonExpression;
import com.redhat.lightblue.query.NaryLogicalExpression;
import com.redhat.lightblue.query.NaryLogicalOperator;
import com.redhat.lightblue.query.NaryValueRelationalExpression;
import com.redhat.lightblue.query.NaryFieldRelationalExpression;
import com.redhat.lightblue.query.NaryRelationalOperator;
import com.redhat.lightblue.query.PartialUpdateExpression;
import com.redhat.lightblue.query.PrimitiveUpdateExpression;
import com.redhat.lightblue.query.QueryExpression;
import com.redhat.lightblue.query.RValueExpression;
import com.redhat.lightblue.query.RegexMatchExpression;
import com.redhat.lightblue.query.SetExpression;
import com.redhat.lightblue.query.Projection;
import com.redhat.lightblue.query.Sort;
import com.redhat.lightblue.query.SortKey;
import com.redhat.lightblue.query.UnaryLogicalExpression;
import com.redhat.lightblue.query.UnaryLogicalOperator;
import com.redhat.lightblue.query.UnsetExpression;
import com.redhat.lightblue.query.UpdateExpression;
import com.redhat.lightblue.query.UpdateExpressionList;
import com.redhat.lightblue.query.Value;
import com.redhat.lightblue.query.ValueComparisonExpression;
import com.redhat.lightblue.util.Error;
import com.redhat.lightblue.util.JsonDoc;
import com.redhat.lightblue.util.JsonNodeCursor;
import com.redhat.lightblue.util.Path;
import com.redhat.lightblue.util.Util;

/**
 * Translations between BSON and JSON. This class is thread-safe, and can be
 * shared between threads
 */
public class Translator {

    public static final String OBJECT_TYPE_STR = "objectType";
    public static final Path OBJECT_TYPE = new Path(OBJECT_TYPE_STR);

    public static final Path ID_PATH = new Path("_id");

    public static final String ERR_NO_OBJECT_TYPE = "NO_OBJECT_TYPE";
    public static final String ERR_INVALID_OBJECTTYPE = "INVALID_OBJECTTYPE";
    public static final String ERR_INVALID_FIELD = "INVALID_FIELD";
    public static final String ERR_INVALID_COMPARISON = "INVALID_COMPARISON";

    private static final Logger LOGGER = LoggerFactory.getLogger(Translator.class);

    private final MetadataResolver mdResolver;
    private final JsonNodeFactory factory;

    private static final Map<BinaryComparisonOperator, String> BINARY_COMPARISON_OPERATOR_JS_MAP;
    private static final Map<BinaryComparisonOperator, String> BINARY_COMPARISON_OPERATOR_MAP;
    private static final Map<NaryLogicalOperator, String> NARY_LOGICAL_OPERATOR_MAP;
    private static final Map<UnaryLogicalOperator, String> UNARY_LOGICAL_OPERATOR_MAP;
    private static final Map<NaryRelationalOperator, String> NARY_RELATIONAL_OPERATOR_MAP;

    private static final String LITERAL_THIS_DOT = "this.";

    static {
        BINARY_COMPARISON_OPERATOR_JS_MAP = new HashMap<>();
        BINARY_COMPARISON_OPERATOR_JS_MAP.put(BinaryComparisonOperator._eq, "==");
        BINARY_COMPARISON_OPERATOR_JS_MAP.put(BinaryComparisonOperator._neq, "!=");
        BINARY_COMPARISON_OPERATOR_JS_MAP.put(BinaryComparisonOperator._lt, "<");
        BINARY_COMPARISON_OPERATOR_JS_MAP.put(BinaryComparisonOperator._gt, ">");
        BINARY_COMPARISON_OPERATOR_JS_MAP.put(BinaryComparisonOperator._lte, "<=");
        BINARY_COMPARISON_OPERATOR_JS_MAP.put(BinaryComparisonOperator._gte, ">=");

        BINARY_COMPARISON_OPERATOR_MAP = new HashMap<>();
        BINARY_COMPARISON_OPERATOR_MAP.put(BinaryComparisonOperator._eq, "$eq");
        BINARY_COMPARISON_OPERATOR_MAP.put(BinaryComparisonOperator._neq, "$ne");
        BINARY_COMPARISON_OPERATOR_MAP.put(BinaryComparisonOperator._lt, "$lt");
        BINARY_COMPARISON_OPERATOR_MAP.put(BinaryComparisonOperator._gt, "$gt");
        BINARY_COMPARISON_OPERATOR_MAP.put(BinaryComparisonOperator._lte, "$lte");
        BINARY_COMPARISON_OPERATOR_MAP.put(BinaryComparisonOperator._gte, "$gte");

        NARY_LOGICAL_OPERATOR_MAP = new HashMap<>();
        NARY_LOGICAL_OPERATOR_MAP.put(NaryLogicalOperator._and, "$and");
        NARY_LOGICAL_OPERATOR_MAP.put(NaryLogicalOperator._or, "$or");

        UNARY_LOGICAL_OPERATOR_MAP = new HashMap<>();
        UNARY_LOGICAL_OPERATOR_MAP.put(UnaryLogicalOperator._not, "$nor"); // Note: _not maps to $nor, not $not. $not applies to operator expression

        NARY_RELATIONAL_OPERATOR_MAP = new HashMap<>();
        NARY_RELATIONAL_OPERATOR_MAP.put(NaryRelationalOperator._in, "$in");
        NARY_RELATIONAL_OPERATOR_MAP.put(NaryRelationalOperator._not_in, "$nin");
    }

    /**
     * Constructs a translator using the given metadata resolver and factory
     */
    public Translator(MetadataResolver mdResolver,
                      JsonNodeFactory factory) {
        this.mdResolver = mdResolver;
        this.factory = factory;
    }

    /**
     * Translate a path to a mongo path
     *
     * Any * in the path is removed. Array indexes remain intact.
     */
    public static String translatePath(Path p) {
        StringBuilder str = new StringBuilder();
        int n = p.numSegments();
        for (int i = 0; i < n; i++) {
            String s = p.head(i);
            if (!s.equals(Path.ANY)) {
                if (i > 0) {
                    str.append('.');
                }
                str.append(s);
            }
        }
        return str.toString();
    }

    /**
     * Translate a path to a javascript path
     *
     * Path cannot have *. Indexes are put into brackets
     */
    public static String translateJsPath(Path p) {
        StringBuilder str = new StringBuilder();
        int n = p.numSegments();
        for (int i = 0; i < n; i++) {
            String s = p.head(i);
            if (s.equals(Path.ANY)) {
                throw Error.get(MongoCrudConstants.ERR_TRANSLATION_ERROR, p.toString());
            } else if (p.isIndex(i)) {
                str.append('[').append(s).append(']');
            } else {
                if (i > 0) {
                    str.append('.');
                }
                str.append(s);
            }
        }
        return str.toString();
    }

    /**
     * Translates a list of JSON documents to DBObjects. Translation is metadata
     * driven.
     */
    public DBObject[] toBson(List<? extends JsonDoc> docs) {
        DBObject[] ret = new DBObject[docs.size()];
        int i = 0;
        for (JsonDoc doc : docs) {
            ret[i++] = toBson(doc);
        }
        return ret;
    }

    /**
     * Translates a JSON document to DBObject. Translation is metadata driven.
     */
    public DBObject toBson(JsonDoc doc) {
        LOGGER.debug("toBson() enter");
        JsonNode node = doc.get(OBJECT_TYPE);
        if (node == null) {
            throw Error.get(ERR_NO_OBJECT_TYPE);
        }
        EntityMetadata md = mdResolver.getEntityMetadata(node.asText());
        if (md == null) {
            throw Error.get(ERR_INVALID_OBJECTTYPE, node.asText());
        }
        DBObject ret = toBson(doc, md);
        LOGGER.debug("toBson() return");
        LOGGER.debug("toBson: in: {}, out: {}",doc,ret);
        return ret;
    }

    /**
     * Traslates a DBObject document to Json document
     */
    public JsonDoc toJson(DBObject object) {
        LOGGER.debug("toJson() enter");
        Object type = object.get(OBJECT_TYPE_STR);
        if (type == null) {
            throw Error.get(ERR_NO_OBJECT_TYPE);
        }
        EntityMetadata md = mdResolver.getEntityMetadata(type.toString());
        if (md == null) {
            throw Error.get(ERR_INVALID_OBJECTTYPE, type.toString());
        }
        JsonDoc doc = toJson(object, md);
        LOGGER.debug("toJson() return");
        return doc;
    }

    /**
     * Translates DBObjects into Json documents
     */
    public List<JsonDoc> toJson(List<DBObject> objects) {
        List<JsonDoc> list = new ArrayList<>(objects.size());
        for (DBObject object : objects) {
            list.add(toJson(object));
        }
        return list;
    }

    /**
     * Add any fields in the old object that are not in the metadata of the new
     * object
     */
    public void addInvisibleFields(DBObject oldDBObject, DBObject newObject, EntityMetadata md) {
        Merge merge = new Merge(md);
        merge.merge(oldDBObject, newObject);
    }

    public static Object getDBObject(DBObject start, Path p) {
        int n = p.numSegments();
        Object trc = start;
        for (int seg = 0; seg < n; seg++) {
            String segment = p.head(seg);
            if (segment.equals(Path.ANY)) {
                throw Error.get(MongoCrudConstants.ERR_TRANSLATION_ERROR, p.toString());
            } else if (Util.isNumber(segment)) {
                trc = ((List) trc).get(Integer.valueOf(segment));
            } else {
                trc = ((DBObject) trc).get(segment);
            }
            if (trc == null) {
                throw Error.get(MongoCrudConstants.ERR_TRANSLATION_ERROR, p.toString());
            }
        }
        return trc;
    }

    /**
     * Translates a sort expression to Mongo sort expression
     */
    public DBObject translate(Sort sort) {
        LOGGER.debug("translate {}", sort);
        Error.push("translateSort");
        DBObject ret;
        try {
            if (sort instanceof CompositeSortKey) {
                ret = translateCompositeSortKey((CompositeSortKey) sort);
            } else {
                ret = translateSortKey((SortKey) sort);
            }
        } catch (Error e) {
            // rethrow lightblue error
            throw e;
        } catch (Exception e) {
            // throw new Error (preserves current error context)
            LOGGER.error(e.getMessage(), e);
            throw Error.get(MongoCrudConstants.ERR_INVALID_OBJECT, e.getMessage());
        } finally {
            Error.pop();
        }
        return ret;
    }

    /**
     * Translates a query to Mongo query
     *
     * @param md Entity metadata
     * @param query The query expression
     */
    public DBObject translate(EntityMetadata md, QueryExpression query) {
        LOGGER.debug("translate {}", query);
        Error.push("translateQuery");
        FieldTreeNode mdRoot = md.getFieldTreeRoot();
        try {
            return translate(mdRoot, query);
        } catch (Error e) {
            // rethrow lightblue error
            throw e;
        } catch (Exception e) {
            // throw new Error (preserves current error context)
            LOGGER.error(e.getMessage(), e);
            throw Error.get(MongoCrudConstants.ERR_INVALID_OBJECT, e.getMessage());
        } finally {
            Error.pop();
        }
    }

    /**
     * Tranlates an update expression to Mongo query
     *
     * @param md Entity metedata
     * @param expr Update expression
     *
     * If the update expresssion is something that can be translated into a
     * mongo update expression, translation is performed. Otherwise,
     * CannotTranslateException is thrown, and the update operation must be
     * performed using the Updaters.
     */
    public DBObject translate(EntityMetadata md, UpdateExpression expr)
            throws CannotTranslateException {
        LOGGER.debug("translate {}", expr);
        Error.push("translateUpdate");
        try {
            BasicDBObject ret = new BasicDBObject();
            translateUpdate(md.getFieldTreeRoot(), expr, ret);
            LOGGER.debug("translated={}", ret);
            return ret;
        } catch (Error | CannotTranslateException e) {
            // rethrow lightblue error
            throw e;
        } catch (Exception e) {
            // throw new Error (preserves current error context)
            LOGGER.error(e.getMessage(), e);
            throw Error.get(MongoCrudConstants.ERR_INVALID_OBJECT, e.getMessage());
        } finally {
            Error.pop();
        }
    }

    /**
     * Returns all the fields required to evaluate the given projection, query, and sort
     *
     * @param md Entity metadata
     * @param p Projection
     * @param q Query
     * @param s Sort
     *
     * All arguments are optional. The returned set contains the
     * fields required to evaluate all the non-null expressions
     */
    public static Set<Path> getRequiredFields(EntityMetadata md,
                                              Projection p,
                                              QueryExpression q,
                                              Sort s) {
        LOGGER.debug("getRequiredFields: p={}, q={}, s={}",p,q,s);
        Set<Path> fields=new HashSet<>();
        FieldCursor cursor=md.getFieldCursor();
        while(cursor.next()) {
            Path field=cursor.getCurrentPath();
            FieldTreeNode node=cursor.getCurrentNode();
            if( (node instanceof ObjectField) ||
                (node instanceof ArrayField &&
                 ((ArrayField)node).getElement() instanceof ObjectArrayElement) ) {
                // include its member fields
            } else if( (p!=null && p.isFieldRequiredToEvaluateProjection(field)) ||
                       (q!=null && q.isRequired(field)) ||
                       (s!=null && s.isRequired(field)) ) {
                LOGGER.debug("{}: required",field);
                fields.add(field);
            } else {
                LOGGER.debug("{}: not required", field);
            }
        }
        return fields;
    }

    /**
     * Writes a MongoDB projection containing fields to evaluate the projection, sort, and query
     */
    public DBObject translateProjection(EntityMetadata md,
                                        Projection p,
                                        QueryExpression q,
                                        Sort s) {
        Set<Path> fields=getRequiredFields(md,p,q,s);
        LOGGER.debug("translateProjection, p={}, q={}, s={}, fields={}",p,q,s,fields);
        BasicDBObject ret=new BasicDBObject();
        for(Path f:fields) {
            ret.append(translatePath(f),1);
        }
        LOGGER.debug("Resulting projection:{}",ret);
        return ret;
    }

    /**
     * Translate update expression list and primitive updates. Anything else
     * causes an exception.
     */
    private void translateUpdate(FieldTreeNode root, UpdateExpression expr, BasicDBObject dest)
            throws CannotTranslateException {
        if (expr instanceof ArrayUpdateExpression) {
            throw new CannotTranslateException(expr);
        } else if (expr instanceof PrimitiveUpdateExpression) {
            translatePrimitiveUpdate(root, (PrimitiveUpdateExpression) expr, dest);
        } else if (expr instanceof UpdateExpressionList) {
            for (PartialUpdateExpression x : ((UpdateExpressionList) expr).getList()) {
                translateUpdate(root, x, dest);
            }
        }
    }

    /**
     * Attempt to translate a primitive update expression. If the epxression
     * touches any arrays or array elements, translation fails.
     */
    private void translatePrimitiveUpdate(FieldTreeNode root,
                                          PrimitiveUpdateExpression expr,
                                          BasicDBObject dest)
            throws CannotTranslateException {
        if (expr instanceof SetExpression) {
            translateSet(root, (SetExpression) expr, dest);
        } else if (expr instanceof UnsetExpression) {
            translateUnset(root, (UnsetExpression) expr, dest);
        } else {
            throw new CannotTranslateException(expr);
        }
    }

    private void translateSet(FieldTreeNode root,
                              SetExpression expr,
                              BasicDBObject dest)
            throws CannotTranslateException {
        String op;
        switch (expr.getOp()) {
            case _set:
                op = "$set";
                break;
            case _add:
                op = "$inc";
                break;
            default:
                throw new CannotTranslateException(expr);
        }
        BasicDBObject obj = (BasicDBObject) dest.get(op);
        if (obj == null) {
            obj = new BasicDBObject();
            dest.put(op, obj);
        }
        for (FieldAndRValue frv : expr.getFields()) {
            Path field = frv.getField();
            if (hasArray(root, field)) {
                throw new CannotTranslateException(expr);
            }
            RValueExpression rvalue = frv.getRValue();
            if (rvalue.getType() == RValueExpression.RValueType._value) {
                Value value = rvalue.getValue();
                FieldTreeNode ftn = root.resolve(field);
                if (ftn == null) {
                    throw new CannotTranslateException(expr);
                }
                if (!(ftn instanceof SimpleField)) {
                    throw new CannotTranslateException(expr);
                }
                Object valueObject = ftn.getType().cast(value.getValue());
                if (field.equals(ID_PATH)) {
                    valueObject = createIdFrom(valueObject);
                }
                obj.put(translatePath(field), valueObject);
            } else {
                throw new CannotTranslateException(expr);
            }
        }
    }

    private void translateUnset(FieldTreeNode root,
                                UnsetExpression expr,
                                BasicDBObject dest)
            throws CannotTranslateException {
        BasicDBObject obj = (BasicDBObject) dest.get("$unset");
        if (obj == null) {
            obj = new BasicDBObject();
            dest.put("$unset", obj);
        }
        for (Path field : expr.getFields()) {
            if (hasArray(root, field)) {
                throw new CannotTranslateException(expr);
            }
            obj.put(translatePath(field), "");
        }
    }

    /**
     * Returns true if the field is an array, or points to a field within an
     * array
     */
    private boolean hasArray(FieldTreeNode root, Path field)
            throws CannotTranslateException {
        FieldTreeNode node = root.resolve(field);
        if (node == null) {
            throw new CannotTranslateException(field);
        }
        do {
            if (node instanceof ArrayField
                    || node instanceof ArrayElement) {
                return true;
            } else {
                node = node.getParent();
            }
        } while (node != null);
        return false;
    }

    private DBObject translateSortKey(SortKey sort) {
        return new BasicDBObject(translatePath(sort.getField()), sort.isDesc() ? -1 : 1);
    }

    private DBObject translateCompositeSortKey(CompositeSortKey sort) {
        DBObject ret = null;
        for (SortKey key : sort.getKeys()) {
            if (ret == null) {
                ret = translateSortKey(key);
            } else {
                ret.put(translatePath(key.getField()), key.isDesc() ? -1 : 1);
            }
        }
        return ret;
    }

    private DBObject translate(FieldTreeNode context, QueryExpression query) {
        DBObject ret;
        if (query instanceof ArrayContainsExpression) {
            ret = translateArrayContains(context, (ArrayContainsExpression) query);
        } else if (query instanceof ArrayMatchExpression) {
            ret = translateArrayElemMatch(context, (ArrayMatchExpression) query);
        } else if (query instanceof FieldComparisonExpression) {
            ret = translateFieldComparison(context, (FieldComparisonExpression) query);
        } else if (query instanceof NaryLogicalExpression) {
            ret = translateNaryLogicalExpression(context, (NaryLogicalExpression) query);
        } else if (query instanceof NaryValueRelationalExpression) {
            ret = translateNaryValueRelationalExpression(context, (NaryValueRelationalExpression) query);
        } else if (query instanceof NaryFieldRelationalExpression) {
            ret = translateNaryFieldRelationalExpression(context, (NaryFieldRelationalExpression) query);
        } else if (query instanceof RegexMatchExpression) {
            ret = translateRegexMatchExpression((RegexMatchExpression) query);
        } else if (query instanceof UnaryLogicalExpression) {
            ret = translateUnaryLogicalExpression(context, (UnaryLogicalExpression) query);
        } else {
            ret = translateValueComparisonExpression(context, (ValueComparisonExpression) query);
        }
        return ret;
    }

    private FieldTreeNode resolve(FieldTreeNode context, Path field) {
        FieldTreeNode node = context.resolve(field);
        if (node == null) {
            throw Error.get(ERR_INVALID_FIELD, field.toString());
        }
        return node;
    }

    /**
     * Converts a value list to a list of values with the proper type
     */
    private List<Object> translateValueList(Type t, List<Value> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(MongoCrudConstants.ERR_EMPTY_VALUE_LIST);
        }
        List<Object> ret = new ArrayList<>(values.size());
        for (Value v : values) {
            Object value = v == null ? null : v.getValue();
            if (value != null) {
                value = t.cast(value);
            }
            ret.add(value);
        }
        return ret;
    }

    private DBObject translateValueComparisonExpression(FieldTreeNode context, ValueComparisonExpression expr) {
        Type t = resolve(context, expr.getField()).getType();
        if (expr.getOp() == BinaryComparisonOperator._eq
                || expr.getOp() == BinaryComparisonOperator._neq) {
            if (!t.supportsEq()) {
                throw Error.get(ERR_INVALID_COMPARISON, expr.toString());
            }
        } else {
            if (!t.supportsOrdering()) {
                throw Error.get(ERR_INVALID_COMPARISON, expr.toString());
            }
        }
        Object valueObject = t.cast(expr.getRvalue().getValue());
        if (expr.getField().equals(ID_PATH)) {
            valueObject = createIdFrom(valueObject);
        }
        if (expr.getOp() == BinaryComparisonOperator._eq) {
            return new BasicDBObject(translatePath(expr.getField()), valueObject);
        } else {
            return new BasicDBObject(translatePath(expr.getField()),
                    new BasicDBObject(BINARY_COMPARISON_OPERATOR_MAP.get(expr.getOp()), valueObject));
        }
    }

    private DBObject translateRegexMatchExpression(RegexMatchExpression expr) {
        StringBuilder options = new StringBuilder();
        BasicDBObject regex = new BasicDBObject("$regex", expr.getRegex());
        if (expr.isCaseInsensitive()) {
            options.append('i');
        }
        if (expr.isMultiline()) {
            options.append('m');
        }
        if (expr.isExtended()) {
            options.append('x');
        }
        if (expr.isDotAll()) {
            options.append('s');
        }
        String opStr = options.toString();
        if (opStr.length() > 0) {
            regex.append("$options", opStr);
        }
        return new BasicDBObject(translatePath(expr.getField()), regex);
    }

    private DBObject translateNaryValueRelationalExpression(FieldTreeNode context, NaryValueRelationalExpression expr) {
        Type t = resolve(context, expr.getField()).getType();
        if (t.supportsEq()) {
            List<Object> values = translateValueList(t, expr.getValues());
            return new BasicDBObject(translatePath(expr.getField()),
                    new BasicDBObject(NARY_RELATIONAL_OPERATOR_MAP.get(expr.getOp()),
                            values));
        } else {
            throw Error.get(ERR_INVALID_FIELD, expr.toString());
        }
    }

    private DBObject translateNaryFieldRelationalExpression(FieldTreeNode context, NaryFieldRelationalExpression expr) {
        Type t = resolve(context, expr.getField()).getType();
        if (t.supportsEq()) {
            // Call resolve, which will verify the field exists.  Don't need the response.
            resolve(context,expr.getRfield());
            boolean in=expr.getOp()==NaryRelationalOperator._in;
            return new BasicDBObject("$where",
                                     String.format("function() for(var nfr=0;nfr<this.%s.length;nfr++) {if ( %s == %s[nfr] ) return %s;} return %s;}",
                                                   translateJsPath(expr.getRfield()),
                                                   translateJsPath(expr.getField()),
                                                   translateJsPath(expr.getRfield()),
                                                   in?"true":"false",
                                                   in?"false":"true"));
        } else {
            throw Error.get(ERR_INVALID_FIELD, expr.toString());
        }
    }

    private DBObject translateUnaryLogicalExpression(FieldTreeNode context, UnaryLogicalExpression expr) {
        List<DBObject> l=new ArrayList<>(1);
        l.add(translate(context,expr.getQuery()));
        return new BasicDBObject(UNARY_LOGICAL_OPERATOR_MAP.get(expr.getOp()), l);
    }

    private DBObject translateNaryLogicalExpression(FieldTreeNode context, NaryLogicalExpression expr) {
        List<QueryExpression> queries = expr.getQueries();
        List<DBObject> list = new ArrayList<>(queries.size());
        for (QueryExpression query : queries) {
            list.add(translate(context, query));
        }
        return new BasicDBObject(NARY_LOGICAL_OPERATOR_MAP.get(expr.getOp()), list);
    }

    private String writeJSForLoop(StringBuilder bld, Path p, String varPrefix) {
        StringBuilder arr = new StringBuilder();
        int n = p.numSegments();
        int j = 0;
        for (int i = 0; i < n; i++) {
            String seg = p.head(i);
            if (Path.ANY.equals(seg)) {
                bld.append(String.format("for(var %s%d=0;%s%d<this.%s.length;%s%d++) {", varPrefix, j, varPrefix, j, arr.toString(), varPrefix, j));
                arr.append('[').append(varPrefix).append(j).append(']');
                j++;
            } else if (p.isIndex(i)) {
                arr.append('[').append(seg).append(']');
            } else {
                if (i > 0) {
                    arr.append('.');
                }
                arr.append(seg);
            }
        }
        return arr.toString();
    }

    private static final String ARR_ARR_EQ= "if(this.f1.length==this.f2.length) { "+
        "  var allEq=true;"+
        "  for(var i=0;i<this.f1.length;i++) { "+
        "     if(this.f1[i] != this.f2[i]) { allEq=false; break; } "+
        "  } "+
        "  if(allEq) return true;"+
        "}";

    private static final String ARR_ARR_NEQ="if(this.f1.length==this.f2.length) { "+
        "  var allEq=true;"+
        "  for(var i=0;i<this.f1.length;i++) { "+
        "     if(this.f1[i] != this.f2[i]) { allEq=false; break; } "+
        "  } "+
        "  if(!allEq) return true;"+
        "} else { return true; }";

    private static final String ARR_ARR_CMP="if(this.f1.length==this.f2.length) {"+
        "  var allOk=true;"+
        "  for(var i=0;i<this.f1.length;i++) {"+
        "    if(!(this.f1[i] op this.f2[i])) {allOk=false; break;} "+
        "  }"+
        " if(allOk) return true;}";


    private String writeArrayArrayComparisonJS(String field1,String field2,BinaryComparisonOperator op) {
        switch(op) {
        case _eq:
            return ARR_ARR_EQ.replaceAll("f1",field1).replaceAll("f2",field2);
        case _neq:
            return ARR_ARR_NEQ.replaceAll("f1",field1).replaceAll("f2",field2);
        default:
            return ARR_ARR_CMP.replaceAll("f1",field1).replaceAll("f2",field2).replace("op",BINARY_COMPARISON_OPERATOR_JS_MAP.get(op));
        }
    }

    private String writeArrayFieldComparisonJS(String field,String array,String op) {
        return String.format("for(var i=0;i<this.%s.length;i++) { if(!(this.%s %s this.%s[i])) return false; } return true;",array,field,op,array);
    }

    private String writeComparisonJS(Path field1,boolean field1IsArray,
                                     Path field2,boolean field2IsArray,
                                     BinaryComparisonOperator op) {
        return writeComparisonJS(translateJsPath(field1),field1IsArray,translateJsPath(field2),field2IsArray,op);
    }

    private String writeComparisonJS(String field1,boolean field1IsArray,
                                     String field2,boolean field2IsArray,
                                     BinaryComparisonOperator op) {
        if(field1IsArray) {
            if(field2IsArray) {
                return writeArrayArrayComparisonJS(field1,field2,op);
            } else {
                return writeArrayFieldComparisonJS(field2,field1,BINARY_COMPARISON_OPERATOR_JS_MAP.get(op.invert()));
            } 
        } else if(field2IsArray) {
            return writeArrayFieldComparisonJS(field1,field2,BINARY_COMPARISON_OPERATOR_JS_MAP.get(op));
        } else {
            return String.format("if(this.%s %s this.%s) { return true;}",field1,BINARY_COMPARISON_OPERATOR_JS_MAP.get(op),field2);
        }
    }

    private DBObject translateFieldComparison(FieldTreeNode context,FieldComparisonExpression expr) {
        StringBuilder str = new StringBuilder(256);
        // We have to deal with array references here
        Path rField = expr.getRfield();
        boolean rIsArray=context.resolve(rField) instanceof ArrayField;
        Path lField = expr.getField();
        boolean lIsArray=context.resolve(lField) instanceof ArrayField;
        int rn = rField.nAnys();
        int ln = lField.nAnys();
        str.append("function() {");
        if (rn > 0 && ln > 0) {
            // Write a function with nested for loops
            // function() {
            //   for(var x1=0;x1<a.b.length;x1++) {
            //     for(var x2=0;x2<a.b[x1].c.d.length;x2++) {
            //        for(var y1=y1<m.n.length;y1++) {
            //        ...
            //       if(this.a.b[x1].x.d[x2] = this.m.n[y1]) return true;
            //      }
            //     }
            //    }
            // return false; }
            String rJSField = writeJSForLoop(str, rField, "r");
            String lJSField = writeJSForLoop(str, lField, "l");
            str.append(writeComparisonJS(lJSField,lIsArray,rJSField,rIsArray,expr.getOp()));
            for (int i = 0; i < rn + ln; i++) {
                str.append('}');
            }
            str.append("return false;}");
        } else if (rn > 0 || ln > 0) {
            // Only one of them has ANY, write a single for loop
            // function() {
            //   for(var x1=0;x1<a.b.length;x1++) {
            //     for(var x2=0;x2<a.b[x1].c.d.length;x2++) {
            //      if(this.a.b[x1].c.d[x2]==this.rfield) return true;
            //     }
            //   }
            //  return false; }
            String jsField = writeJSForLoop(str, rn > 0 ? rField : lField, "i");
            str.append(writeComparisonJS(ln > 0 ? jsField : translateJsPath(lField),lIsArray,
                                         rn > 0 ? jsField : translateJsPath(rField),rIsArray,
                                         expr.getOp()));
            for (int i = 0; i < rn + ln; i++) {
                str.append('}');
            }
            str.append("return false;}");
        } else {
            // No ANYs, direct comparison
            //  function() {return this.lfield = this.rfield}
            str.append(writeComparisonJS(lField,lIsArray,rField,rIsArray,expr.getOp()));
            str.append("return false;}");
        }
        
        return new BasicDBObject("$where", str.toString());
    }

    private DBObject translateArrayElemMatch(FieldTreeNode context, ArrayMatchExpression expr) {
        FieldTreeNode arrayNode = resolve(context, expr.getArray());
        if (arrayNode instanceof ArrayField) {
            ArrayElement el = ((ArrayField) arrayNode).getElement();
            if (el instanceof ObjectArrayElement) {
                return new BasicDBObject(translatePath(expr.getArray()),
                        new BasicDBObject("$elemMatch",
                                translate(el, expr.getElemMatch())));
            }
        }
        throw Error.get(ERR_INVALID_FIELD, expr.toString());
    }

    private DBObject translateArrayContains(FieldTreeNode context, ArrayContainsExpression expr) {
        DBObject ret = null;
        FieldTreeNode arrayNode = resolve(context, expr.getArray());
        if (arrayNode instanceof ArrayField) {
            Type t = ((ArrayField) arrayNode).getElement().getType();
            switch (expr.getOp()) {
                case _all:
                    ret = translateArrayContainsAll(t, expr.getArray(), expr.getValues());
                    break;
                case _any:
                    ret = translateArrayContainsAny(t, expr.getArray(), expr.getValues());
                    break;
                case _none:
                    ret = translateArrayContainsNone(t, expr.getArray(), expr.getValues());
                    break;
            }
        } else {
            throw Error.get(ERR_INVALID_FIELD, expr.toString());
        }
        return ret;
    }

    /**
     * <pre>
     *   { field : { $all:[values] } }
     * </pre>
     */
    private DBObject translateArrayContainsAll(Type t, Path array, List<Value> values) {
        return new BasicDBObject(translatePath(array),
                new BasicDBObject("$all",
                        translateValueList(t, values)));
    }

    /**
     * <pre>
     *     { $or : [ {field:value1},{field:value2},...] }
     * </pre>
     */
    private DBObject translateArrayContainsAny(Type t, Path array, List<Value> values) {
        List<BasicDBObject> l = new ArrayList<>(values.size());
        for (Value x : values) {
            l.add(new BasicDBObject(translatePath(array), x == null ? null
                    : x.getValue() == null ? null : t.cast(x.getValue())));
        }
        return new BasicDBObject("$or", l);
    }

    /**
     * <pre>
     * { $not : { $or : [ {field:value1},{field:value2},...]}}
     * </pre>
     */
    private DBObject translateArrayContainsNone(Type t, Path array, List<Value> values) {
        return new BasicDBObject("$not", translateArrayContainsAny(t, array, values));
    }

    private JsonDoc toJson(DBObject object, EntityMetadata md) {
        // Translation is metadata driven. We don't know how to
        // translate something that's not defined in metadata.
        FieldCursor cursor = md.getFieldCursor();
        if (cursor.firstChild()) {
            return new JsonDoc(objectToJson(object, md, cursor));
        } else {
            return null;
        }
    }

    /**
     * Called after firstChild is called on cursor
     */
    private ObjectNode objectToJson(DBObject object, EntityMetadata md, FieldCursor mdCursor) {
        ObjectNode node = factory.objectNode();
        do {
            Path p = mdCursor.getCurrentPath();
            FieldTreeNode field = mdCursor.getCurrentNode();
            String fieldName = field.getName();
            LOGGER.debug("{}", p);
            // Retrieve field value
            Object value = object.get(fieldName);
            if (value != null) {
                if (field instanceof SimpleField) {
                    convertSimpleFieldToJson(node, field, value, fieldName);
                } else if (field instanceof ObjectField) {
                    convertObjectFieldToJson(node, fieldName, md, mdCursor, value, p);
                } else if (field instanceof ArrayField && value instanceof List && mdCursor.firstChild()) {
                    convertArrayFieldToJson(node, fieldName, md, mdCursor, value);
                } else if (field instanceof ReferenceField) {
                    convertReferenceFieldToJson();
                }
            } else
                node.set(fieldName,factory.nullNode());
        } while (mdCursor.nextSibling());
        return node;
    }

    private void convertSimpleFieldToJson(ObjectNode node, FieldTreeNode field, Object value, String fieldName) {
        JsonNode valueNode = field.getType().toJson(factory, value);
        if (valueNode != null) {
            node.set(fieldName, valueNode);
        }
    }

    private void convertObjectFieldToJson(ObjectNode node, String fieldName, EntityMetadata md, FieldCursor mdCursor, Object value, Path p) {
        if (value instanceof DBObject) {
            if (mdCursor.firstChild()) {
                JsonNode valueNode = objectToJson((DBObject) value, md, mdCursor);
                if (valueNode != null) {
                    node.set(fieldName, valueNode);
                }
                mdCursor.parent();
            }
        } else {
            LOGGER.error("Expected DBObject, found {} for {}", value.getClass(), p);
        }
    }

    @SuppressWarnings("rawtypes")
    private void convertArrayFieldToJson(ObjectNode node, String fieldName, EntityMetadata md, FieldCursor mdCursor, Object value) {
        ArrayNode valueNode = factory.arrayNode();
        node.set(fieldName, valueNode);
        // We must have an array element here
        FieldTreeNode x = mdCursor.getCurrentNode();
        if (x instanceof ArrayElement) {
            for (Object item : (List) value) {
                valueNode.add(arrayElementToJson(item, (ArrayElement) x, md, mdCursor));
            }
        }
        mdCursor.parent();
    }

    private void convertReferenceFieldToJson() {
        //TODO
        LOGGER.debug("Converting reference field: ");
    }

    private JsonNode arrayElementToJson(Object value,
                                        ArrayElement el,
                                        EntityMetadata md,
                                        FieldCursor mdCursor) {
        JsonNode ret = null;
        if (el instanceof SimpleArrayElement) {
            if (value != null) {
                ret = el.getType().toJson(factory, value);
            }
        } else {
            if (value != null) {
                if (value instanceof DBObject) {
                    if (mdCursor.firstChild()) {
                        ret = objectToJson((DBObject) value, md, mdCursor);
                        mdCursor.parent();
                    }
                } else {
                    LOGGER.error("Expected DBObject, got {}", value.getClass().getName());
                }
            }
        }
        return ret;
    }

    private BasicDBObject toBson(JsonDoc doc, EntityMetadata md) {
        LOGGER.debug("Entity: {}", md.getName());
        BasicDBObject ret = null;
        JsonNodeCursor cursor = doc.cursor();
        if (cursor.firstChild()) {
            ret = objectToBson(cursor, md);
        }
        return ret;
    }

    private Object toValue(Type t, JsonNode node) {
        if (node == null || node instanceof NullNode) {
            return null;
        } else {
            return t.fromJson(node);
        }
    }

    private void toBson(BasicDBObject dest,
                        SimpleField fieldMd,
                        Path path,
                        JsonNode node) {
        Object value = toValue(fieldMd.getType(), node);
        // Should we add fields with null values to the bson doc? Answer: yes
        if (value != null) {
            LOGGER.debug("{} = {}", path, value);
            if (path.equals(ID_PATH)) {
                value = createIdFrom(value);
            }
            // Store big values as string. Mongo does not support big values
            if (value instanceof BigDecimal || value instanceof BigInteger) {
                value = value.toString();
            }

            dest.append(path.tail(0), value);
        } else
            dest.append(path.tail(0), null);
    }

    /**
     * @param cursor The cursor, pointing to the first element of the object
     */
    private BasicDBObject objectToBson(JsonNodeCursor cursor, EntityMetadata md) {
        BasicDBObject ret = new BasicDBObject();
        do {
            Path path = cursor.getCurrentPath();
            JsonNode node = cursor.getCurrentNode();
            LOGGER.debug("field: {}", path);
            FieldTreeNode fieldMdNode = md.resolve(path);
            if (fieldMdNode == null) {
                throw Error.get(ERR_INVALID_FIELD, path.toString());
            }

            if (fieldMdNode instanceof SimpleField) {
                toBson(ret, (SimpleField) fieldMdNode, path, node);
            } else if (fieldMdNode instanceof ObjectField) {
                convertObjectFieldToBson(node, cursor, ret, path, md);
            } else if (fieldMdNode instanceof ArrayField) {
                convertArrayFieldToBson(node, cursor, ret, fieldMdNode, path, md);
            } else if (fieldMdNode instanceof ReferenceField) {
                convertReferenceFieldToBson();
            }
        } while (cursor.nextSibling());
        return ret;
    }

    private void convertObjectFieldToBson(JsonNode node, JsonNodeCursor cursor, BasicDBObject ret, Path path, EntityMetadata md) {
        if (node != null) {
            if (node instanceof ObjectNode) {
                if (cursor.firstChild()) {
                    ret.append(path.tail(0), objectToBson(cursor, md));
                    cursor.parent();
                }
            } else if(node instanceof NullNode) {
                ret.append(path.tail(0),null);
            } else {
                throw Error.get(ERR_INVALID_FIELD, path.toString());
            }
        }
    }

    private void convertArrayFieldToBson(JsonNode node, JsonNodeCursor cursor, BasicDBObject ret, FieldTreeNode fieldMdNode, Path path, EntityMetadata md) {
        if (node != null) {
            if (node instanceof ArrayNode) {
                if (cursor.firstChild()) {
                    ret.append(path.tail(0), arrayToBson(cursor, ((ArrayField) fieldMdNode).getElement(), md));
                    cursor.parent();
                } else {
                    // empty array! add an empty list.
                    ret.append(path.tail(0), new ArrayList());
                }
            } else if(node instanceof NullNode) {
                ret.append(path.tail(0),null);
            } else {
                throw Error.get(ERR_INVALID_FIELD, path.toString());
            }
        }
    }

    private void convertReferenceFieldToBson() {
        //TODO
        throw new java.lang.UnsupportedOperationException();
    }

    /**
     * @param cursor The cursor, pointing to the first element of the array
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private List arrayToBson(JsonNodeCursor cursor, ArrayElement el, EntityMetadata md) {
        List l = new ArrayList();
        if (el instanceof SimpleArrayElement) {
            Type t = el.getType();
            do {
                Object value = toValue(t, cursor.getCurrentNode());
                l.add(value);
            } while (cursor.nextSibling());
        } else {
            do {
                JsonNode node = cursor.getCurrentNode();
                if (node == null || node instanceof NullNode) {
                    l.add(null);
                } else {
                    if (cursor.firstChild()) {
                        l.add(objectToBson(cursor, md));
                        cursor.parent();
                    } else {
                        l.add(null);
                    }
                }
            } while (cursor.nextSibling());
        }
        return l;
    }

    /**
     * Creates appropriate identifier object given source data. If the source
     * can be converted to an ObjectId it is, else it is returned as a String.
     *
     * @param source input data
     * @return ObjectId if possible else String
     */
    public static Object createIdFrom(Object source) {
        if (source == null) {
            return null;
        } else if (ObjectId.isValid(source.toString())) {
            return new ObjectId(source.toString());
        } else {
            return source.toString();
        }
    }
}
