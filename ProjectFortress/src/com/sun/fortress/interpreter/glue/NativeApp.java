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

package com.sun.fortress.interpreter.glue;

import com.sun.fortress.nodes_util.NodeUtil;
import com.sun.fortress.nodes_util.NodeFactory;

import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import edu.rice.cs.plt.tuple.Option;

import com.sun.fortress.interpreter.evaluator.values.FValue;
import com.sun.fortress.interpreter.evaluator.values.NativeConstructor;
import com.sun.fortress.nodes.NodeVisitor;
import com.sun.fortress.nodes.NodeVisitor_void;
import com.sun.fortress.nodes.TabPrintWriter;
import com.sun.fortress.nodes.Expr;
import com.sun.fortress.nodes.ExprMI;
import com.sun.fortress.nodes.FnHeader;
import com.sun.fortress.nodes.Id;
import com.sun.fortress.nodes.IdOrOpOrAnonymousName;
import com.sun.fortress.nodes.Juxt;
import com.sun.fortress.nodes.MathItem;
import com.sun.fortress.nodes.MathPrimary;
import com.sun.fortress.nodes.Param;
import com.sun.fortress.nodes.StaticParam;
import com.sun.fortress.nodes.StringLiteralExpr;
import com.sun.fortress.nodes.Type;
import com.sun.fortress.nodes.VarRef;
import com.sun.fortress.nodes.WhereClause;
import com.sun.fortress.nodes.Applicable;
import com.sun.fortress.nodes_util.NodeUtil;
import com.sun.fortress.useful.Pair;
import static com.sun.fortress.exceptions.InterpreterBug.bug;
import static com.sun.fortress.exceptions.ProgramError.error;

/**
 * A NativeApp indicates that an action is implemented natively; the
 * type itself is abstract.
 *
 * Programmers writing a NativeApp should implement getArity and ApplyToArgs.
 *
 * We might want some way to sanity-check the expected type of a
 * native thing against the actual type declared in the applicable
 * object passed in.  Right now we just check arities.
 */
public abstract class NativeApp implements Applicable {
    protected Applicable a;

    /**
     * A NativeApp has a fixed arity by default.  This defines that
     * fixed arity.  This is used in the checking performed by
     * setParams and by applyInner.  If you need a multi-arity
     * function you will need to override those two methods as
     * well. */
    public abstract int getArity();

    /**
     * Set the delegate to a, after performing an arity check and
     * making sure the return type is defined.  Override this only if
     * defining a function whose arity is not fixed, or you wish to
     * perform additional sanity checks on the Fortress-side function
     * definition.
     */
    protected void init(Applicable app, boolean isFunctionalMethod) {
        if (this.a!=null) {
            bug("Duplicate NativeApp.init call.");
        }
        this.a = app;
        int aty = NodeUtil.getParams(app).size();
        // Dock functional methods by 1 for the self parameter.
        // This lets us treat methods and functional methods identically
        // on the native Java side, which simplifies life immensely.
        if (isFunctionalMethod) aty--;
        if (aty != getArity()) {
            error(app, "Arity of type "+aty
                       +" does not match native arity "+getArity());
        }
        if (NodeUtil.getReturnType(app)==null || NodeUtil.getReturnType(app).isNone()) {
            error(app,"Please specify a Fortress return type.");
        }
    }

    /* Except for getBody() these just delegate to a. */
    public Expr getBody() { return null; }
    public FnHeader getHeader() { return a.getHeader(); }
    public List<Param> getParams() { return a.getHeader().getParams(); }
    public Option<Type> getReturnType() { return a.getHeader().getReturnType(); }
    public List<StaticParam> getStaticParams() {
        return a.getHeader().getStaticParams();
    }
    public IdOrOpOrAnonymousName getName() { return a.getHeader().getName(); }
    public Option<WhereClause> getWhereClause() { return a.getHeader().getWhereClause(); }
    public String at() { return a.at(); }
    public String stringName() { return a.stringName(); }

    public String toString() {
        return (a.stringName()+"(native " + this.getClass().getSimpleName()+")");
    }

    public <RetType> RetType accept(NodeVisitor<RetType> visitor) {
        return visitor.forId(NodeFactory.makeId(NodeFactory.makeSpan(""), ""));
    }
    public void accept(NodeVisitor_void visitor) {}
    public int generateHashCode() { return 0; }
    public java.lang.String serialize() { return ""; }
    public void serialize(java.io.Writer writer) {}
    public void outputHelp(TabPrintWriter writer, boolean lossless) {}

    /**
     * Actually apply the native function to the passed-in arguments.
     * Called by Closure.applyInner.  Arity and type checking have already
     * occurred and can be assumed.  For fixed-arity primitives this
     * method ought to simply unpack the arguments and chain to
     * another method defined in the subclass.  RuntimeExceptions and
     * Errors are caught and wrapped by applyInner, which also adds
     * location information to ProgramErrors.
     */
    public abstract FValue applyToArgs(List<FValue> args);

    /* Does the passed-in method body represent a native function?  If
     * so, return the corresponding native action.
     * Otherwise, return null.
     *
     * Right now this does a naive pattern-match for the expression:
     *
     * builtinPrimitive("name.of.java.class.as.literal.String")
     *
     * Using Java reflection we load and construct an instance of this
     * class, failing somewhat-gracefully if any part of the attempt
     * fails.  The error checking is gratuitously detailed so the
     * library hacker can sort out what is going on when things break.
     */
    public static Applicable checkAndLoadNative(Applicable defn,
                                                boolean isFunctionalMethod) {
        Option<Expr> optBody = NodeUtil.getBody(defn);
        if (optBody.isNone()) return defn;
        Expr body = optBody.unwrap();
        Expr fn;
        Expr arg;
        if ( body instanceof Juxt &&
             ((Juxt)body).isTight() ) {
            List<Expr> juxts = ((Juxt)body).getExprs();
            if (juxts.size()!=2) return defn;
            fn = juxts.get(0);
            arg = juxts.get(1);
        } else if (body instanceof MathPrimary) {
            MathPrimary mp = (MathPrimary)body;
            List<MathItem> args = mp.getRest();
            if (args.size()!=1) return defn;
            fn = mp.getFront();
            MathItem mi = args.get(0);
            if (mi instanceof ExprMI) arg = ((ExprMI)mi).getExpr();
            else return defn;
        } else // (!(body instanceof TightJuxt || body instanceof MathPrimary))
            return defn;
        if (!(fn instanceof VarRef)) return defn;
        if (!(arg instanceof StringLiteralExpr)) return defn;
        Id name = ((VarRef)fn).getVarId();
        if (!name.getText().equals("builtinPrimitive")) return defn;
        String str = ((StringLiteralExpr)arg).getText();
        Pair<String, Applicable> key = new Pair<String, Applicable>(str, defn);
        synchronized(cache) {
            NativeApp res = cache.get(key);
            if (res != null)
                return res;
            try {
                // System.err.println("Loading primitive class "+str);
                Class nativeAct = Class.forName(str);
                res = (NativeApp)nativeAct.newInstance();
                res.init(defn,isFunctionalMethod);
                cache.put(key, res);
                return res;
            } catch (java.lang.ClassNotFoundException x) {
                return bug(defn,"Native class "+str +" not found.",x);
            } catch (java.lang.InstantiationException x) {
                return bug(defn,"Native class "+str +" has no nullary constructor.",x);
            } catch (java.lang.IllegalAccessException x) {
                return bug(defn,"Native class "+str +" cannot be accessed.",x);
            } catch (java.lang.ClassCastException x) {
                return bug(defn,"Native class "+str +" is not a NativeApp.",x);
            }
        }
    }
    public static Applicable checkAndLoadNative(Applicable defn) {
        return checkAndLoadNative(defn, false);
    }
    static public void reset() {
        cache = new Hashtable<Pair<String, Applicable>, NativeApp>();
        NativeConstructor.unregisterAllConstructors();
    }
    static Map<Pair<String, Applicable>, NativeApp> cache;
}
