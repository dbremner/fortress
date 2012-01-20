/*******************************************************************************
    Copyright 2008 Sun Microsystems, Inc.,
    4150 Network Circle, Santa Clara, California 95054, U.S.A.
    All rights reserved.

    U.S. Government Rights - Commercial software.
    Government users are subject to the Sun Microsystems, Inc. standard
    license agreement and applicable provisions of the FAR and its supplements.

    Use is subject to license terms.

    This distribution may include materials developed by third parties.

    Sun, Sun Microsystems, the Sun logo and Java are trademarks or registered
    trademarks of Sun Microsystems, Inc. in the U.S. and other countries.
 ******************************************************************************/

package com.sun.fortress.syntax_abstractions.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import com.sun.fortress.nodes.APIName;
import com.sun.fortress.nodes.AsIfExpr;
import com.sun.fortress.nodes.Assignment;
import com.sun.fortress.nodes.Block;
import com.sun.fortress.nodes.Do;
import com.sun.fortress.nodes.DoFront;
import com.sun.fortress.nodes.Enclosing;
import com.sun.fortress.nodes.EnclosingFixity;
import com.sun.fortress.nodes.Expr;
import com.sun.fortress.nodes.Fixity;
import com.sun.fortress.nodes.Id;
import com.sun.fortress.nodes.TemplateGap;
import com.sun.fortress.nodes.VarType;
import com.sun.fortress.nodes.TraitType;
import com.sun.fortress.nodes.IntLiteralExpr;
import com.sun.fortress.nodes.LValue;
import com.sun.fortress.nodes.LValueBind;
import com.sun.fortress.nodes.LocalVarDecl;
import com.sun.fortress.nodes.Op;
import com.sun.fortress.nodes.OpName;
import com.sun.fortress.nodes.OpRef;
import com.sun.fortress.nodes.OpExpr;
import com.sun.fortress.nodes.StaticArg;
import com.sun.fortress.nodes.TightJuxt;
import com.sun.fortress.nodes.BaseType;
import com.sun.fortress.nodes.TupleExpr;
import com.sun.fortress.nodes.Type;
import com.sun.fortress.nodes.TypeArg;
import com.sun.fortress.nodes.VarDecl;
import com.sun.fortress.nodes.VarRef;
import com.sun.fortress.nodes.VoidLiteralExpr;
import com.sun.fortress.nodes_util.NodeFactory;
import com.sun.fortress.nodes_util.NodeUtil;
import com.sun.fortress.nodes_util.Span;
import com.sun.fortress.syntax_abstractions.rats.util.FreshName;

import edu.rice.cs.plt.tuple.Option;

public class SyntaxAbstractionUtil {

    public static final String FORTRESSAST = "FortressAst";
    public static final String FORTRESSBUILTIN = "FortressBuiltin";
    public static final String STRINGLITERALEXPR = "StringLiteralExpr";
    public static final String STRINGLITERAL = "StringLiteral";
    public static final String FORTRESSLIBRARY = "FortressLibrary";
    public static final String MAYBE = "Maybe";
    public static final String JUST = "Just";
    public static final String NOTHING = "Nothing";
    public static final String STRING = "String";
    public static final String LIST = "List";

    /**
     * Returns a qualified id name where the grammar name is added to the api.
     * E.g. api: Foo.Bar, grammar name Baz, and member Gnu gives
     * APIName: Foo.Bar.Baz and id: Gnu.
     */
    public static Id qualifyMemberName(APIName api, String currentName, String uqname) {
        Collection<Id> names = new LinkedList<Id>();
        names.addAll(api.getIds());
        names.add(NodeFactory.makeId(currentName));
        APIName apiGrammar = NodeFactory.makeAPIName(names);
        Id uqName = NodeFactory.makeId(uqname);
        return NodeFactory.makeId(apiGrammar, uqName);
    }

    /**
     * We assume that the grammar name has been disambiguated and
     * thus has an api.
     * @param grammarName
     * @param memberName
     * @return
     */
    public static Id memberName(Span span, Id grammarName, String memberName) {
        Collection<Id> names = new LinkedList<Id>();
        assert(grammarName.getApi().isSome());
        names.addAll(grammarName.getApi().unwrap().getIds());
        names.add(NodeFactory.makeId(grammarName.getText()));
        APIName apiGrammar = NodeFactory.makeAPIName(names);
        return NodeFactory.makeId(span, apiGrammar, memberName);
    }

    /**
     * Create a Java representation of a Fortress AST which when evaluated
     * instantiates a new object of the given name from the given api, with the given option
     * as argument.
     * @param span
     * @param apiName
     * @param objectName
     * @param arg
     * @return
     */
    public static Expr makeObjectInstantiation(Span span, String apiName,
            String objectName, List<Expr> args, List<StaticArg> staticArgs) {
        List<Expr> exprs = new LinkedList<Expr>();
        Id name = NodeFactory.makeId(apiName, objectName);
        exprs.add(NodeFactory.makeFnRef(span, name, staticArgs));
        if (args.isEmpty()) {
            exprs.add(new VoidLiteralExpr());
        }
        else {
            exprs.add(new TupleExpr(args));
        }
        return NodeFactory.makeTightJuxt(span, exprs);
    }

    public static Expr makeNoParamObjectInstantiation(Span span, String apiName, String objectName, List<StaticArg> staticArgs) {
        Id name = NodeFactory.makeId(apiName, objectName);
        return NodeFactory.makeFnRef(span, name, staticArgs);
    }

    public static Expr makeVoidObjectInstantiation(Span span, String apiName, String objectName, List<Expr> args) {
        return makeObjectInstantiation(span, apiName, objectName, args, new LinkedList<StaticArg>());
    }

    public static BaseType unwrap(Option<BaseType> t) {
        if (t.isNone()) {
            throw new RuntimeException("Grammar member declaration does not have a type, malformed AST");
        }
        return t.unwrap();
    }

    public static Expr makeList(Span span, List<Expr> args, String typeName) {
        List<OpName> ops = new LinkedList<OpName>();
        OpName opName = new Enclosing(span, new Op("<|", Option.<Fixity>some(new EnclosingFixity())), new Op("|>", Option.<Fixity>some(new EnclosingFixity())));
        ops.add(opName);

        List<StaticArg> staticArgs = new LinkedList<StaticArg>();
        Type type = new VarType(span, NodeFactory.makeId(typeName));
        staticArgs.add(new TypeArg(type));

        OpRef opRef = new OpRef(span, opName, ops, staticArgs);

        List<Expr> exprs = new LinkedList<Expr>();
        if (args.isEmpty()) {
            return SyntaxAbstractionUtil.makeObjectInstantiation(span, "List", "emptyList", new LinkedList<Expr>(), staticArgs);
        }
        else {
            args.add(0, new AsIfExpr(args.remove(0), type));
            exprs.add(new TupleExpr(args));
        }
        return new OpExpr(span, opRef, exprs );
    }

    private static Expr makeLocalVarDecl(Span span, String freshName, String lastFreshName, List<StaticArg> staticArgs, Expr expr, List<Expr> newBody) {
        List<LValue> lhs = new LinkedList<LValue>();
        Id freshVar = NodeFactory.makeId(freshName);
        Option<Type> type = Option.<Type>some(new TraitType(NodeFactory.makeId("List", "List"), staticArgs));
        lhs.add(new LValueBind(span, freshVar, type , false));

        Id name = NodeFactory.makeId(lastFreshName, "addRight");
        List<Expr> exprs = new LinkedList<Expr>();
        exprs.add(NodeFactory.makeFnRef(span, name));
        List<Expr> args = new LinkedList<Expr>();
        args.add(expr);
        exprs.add(new TupleExpr(args));
        Option<Expr> rhs = Option.<Expr>some(new TightJuxt(exprs));

        return new LocalVarDecl(span, newBody, lhs, rhs);
    }

    public static Expr makeMaybe(Span span, Option<Expr> op, String typeArg) {
        List<StaticArg> maybeStaticArgs = new LinkedList<StaticArg>();
        maybeStaticArgs.add(new TypeArg(new VarType(NodeFactory.makeId(FORTRESSAST, typeArg))));
        if (op.isSome()) {
            List<Expr> justArgs = new LinkedList<Expr>();
            justArgs.add(op.unwrap());
            return SyntaxAbstractionUtil.makeObjectInstantiation(span, FORTRESSAST, JUST, justArgs, maybeStaticArgs);
        }
        else {
            return SyntaxAbstractionUtil.makeNoParamObjectInstantiation(span, FORTRESSAST, NOTHING, maybeStaticArgs);
        }
    }

    public static String makeTemplateGap(List<String> code, List<Integer> indent, String basetype, String idName, String params, String sVarName) {
        String templateName = FreshName.getFreshName("template");
        indent.add(3);
        code.add(String.format("if (%s == null) %s = new LinkedList<Id>();", params, params));
        indent.add(3);
        code.add(String.format("TemplateGap%s %s = ExprFactory.makeTemplateGap%s(%s, %s, %s);", basetype, templateName, basetype, sVarName, idName, params));
        return templateName;
    }

    // TODO: add span
    public static <T extends TemplateGap> T makeTemplateGap(Span span, Class<T> cls, Id id, List<Id> params) {
        try {
            Constructor<T> con = cls.getDeclaredConstructor();
            con.setAccessible(true);
            T t = (T) con.newInstance();
            Field fld = cls.getDeclaredField("_id");
            fld.setAccessible(true);
            fld.set(t, id);
            fld = cls.getDeclaredField("_params");
            fld.setAccessible(true);
            fld.set(t, params);
            fld = getField(cls, "_span");
            fld.setAccessible(true);
            fld.set(t, span);
            return t;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
    
    private static <T extends TemplateGap> Field getField(Class<T> cls, String fieldName) throws SecurityException, NoSuchFieldException {
        boolean done = false;
        Class<T> cl = cls;
        while (!done) {
            for (Field f: cl.getDeclaredFields()) {
                if (f.getName().equals(fieldName)) {
                    return cl.getDeclaredField(fieldName);
                }
            }
            cl = (Class<T>) cl.getSuperclass();
            if (cl == null) {
                return null;        
            }
        }
        return null;
    }

}