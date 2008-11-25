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

package com.sun.fortress.interpreter.evaluator;

import java.util.Iterator;
import java.util.List;
import edu.rice.cs.plt.tuple.Option;

import com.sun.fortress.useful.Useful;
import com.sun.fortress.exceptions.FortressException;
import com.sun.fortress.interpreter.env.LazilyEvaluatedCell;
import com.sun.fortress.interpreter.evaluator.types.FTraitOrObjectOrGeneric;
import com.sun.fortress.interpreter.evaluator.types.FType;
import com.sun.fortress.interpreter.evaluator.types.FTypeGeneric;
import com.sun.fortress.interpreter.evaluator.types.FTypeObject;
import com.sun.fortress.interpreter.evaluator.types.FTypeTop;
import com.sun.fortress.interpreter.evaluator.types.FTypeTrait;
import com.sun.fortress.interpreter.evaluator.types.SymbolicType;
import com.sun.fortress.interpreter.evaluator.types.SymbolicWhereType;
import com.sun.fortress.interpreter.evaluator.types.TypeGeneric;
import com.sun.fortress.interpreter.evaluator.values.Closure;
import com.sun.fortress.interpreter.evaluator.values.Constructor;
import com.sun.fortress.interpreter.evaluator.values.FGenericFunction;
import com.sun.fortress.interpreter.evaluator.values.FValue;
import com.sun.fortress.interpreter.evaluator.values.FVoid;
import com.sun.fortress.interpreter.evaluator.values.Fcn;
import com.sun.fortress.interpreter.evaluator.values.GenericConstructor;
import com.sun.fortress.interpreter.evaluator.values.GenericSingleton;
import com.sun.fortress.interpreter.evaluator.values.Simple_fcn;
import com.sun.fortress.interpreter.glue.WellKnownNames;
import com.sun.fortress.nodes.*;
import com.sun.fortress.nodes.Applicable;
import com.sun.fortress.nodes_util.ExprFactory;
import com.sun.fortress.nodes_util.NodeUtil;
import com.sun.fortress.useful.HasAt;

import static com.sun.fortress.exceptions.InterpreterBug.bug;
import static com.sun.fortress.exceptions.ProgramError.error;
import static com.sun.fortress.exceptions.ProgramError.errorMsg;

/**
 * This comment is not yet true; it is a goal.
 *
 * BuildEnvironments is a multiple-pass visitor pattern.
 *
 * The first pass, applied to a node that contains things (for example, a
 * component contains top-level declarations, a trait contains method
 * declarations) it creates entries for those things in the bindInto
 * environment.  In the top-level environment, traits and objects export
 * the names and definitions for the functional methods that they contain.
 *
 * The bindings created are not complete after the first pass.
 *
 * The second pass completes the type initialization. For contained things that
 * have internal structure (e.g., a trait within a top level list) this may
 * require a recursive visit, but with a newly allocated environment running its
 * first and second passes. This includes singleton object types.
 *
 * The third pass initializes functions and methods; these may depend on types.
 * The third pass must extract functional methods from traits and objects.
 *
 * The fourth pass performs value initialization. These may depend on functions.
 * This includes singleton object values.
 *
 * The evaluation order is slightly relaxed to make the interpreter tractable;
 * value cells (and variable cells?) are initialized with thunks. (How do we
 * thunk a singleton object?)
 *
 * It may be necessary to thunk the types as well; this is not yet entirely
 * clear because the type system is so complex. Because types already contain
 * references to their defining environment, this may proceed in an ad-hoc
 * fashion with lazy memoization.
 *
 * Note that not all passes are required in all contexts; only the top level has
 * the combination of types, functions, variables, and unordered access.
 * Different initializations are assigned to different (numbered) passes so that
 * environment building in some contexts can skip passes (for example, skip the
 * type pass in any non-top-level environment).
 *
 */
public class BuildEnvironments extends NodeAbstractVisitor<Boolean> {


     private int pass = 1;

    public void resetPass() {
        setPass(1);
    }

    public void assertPass(int p) {
        if (getPass() != p)
            bug("Expected pass " + p + " got pass " + getPass());
    }

    public void secondPass() {
        assertPass(1);
        setPass(2);
        // An environment must be blessed before it can be cloned.
        bindInto.bless();
    }

    public void thirdPass() {
        assertPass(2);
        setPass(3);
    }

    public void fourthPass() {
        assertPass(3);
        setPass(4);
    }

    public void visit(CompilationUnit n) {
        n.accept(this);
    }

    Environment containing;

    Environment bindInto;

    /**
     * Creates an environment builder that will inject bindings into 'within'.
     * The visit is suspended at generics (com.sun.fortress.interpreter.nodes
     * with type parameters) until they can be instantiated.
     */
    public BuildEnvironments(Environment within) {
        this.containing = within;
        this.bindInto = within;
    }

    protected BuildEnvironments(Environment within, Environment bind_into) {
        this.containing = within;
        this.bindInto = bind_into;
    }

    private BuildEnvironments(Environment within, int pass) {
        this.containing = within;
        this.bindInto = within;
        this.setPass(pass);
    }

    public Environment getEnvironment() {
        return containing;
    }

    static Closure instantiate(FGenericFunction x) {
        return null;
    }

    static Constructor instantiate(GenericConstructor x) {
        return null;
    }

    static FTypeTrait instantiate(TypeGeneric x) {
        return null;
    }




     protected static void doDefs(BuildEnvironments inner, List<Decl> defs) {
        for (Decl def : defs) {
            def.accept(inner);
        }
    }

    protected void doDefs(List<Decl> defs) {
        for (Decl def : defs) {
            def.accept(this);
        }
    }

//    /**
//     * Put the mappings into "into", but create closures against forTraitMethods.
//     *
//     * @param into
//     * @param forTraitMethods
//     * @param defs
//     * @param fields
//     */
//    private void doTraitMethodDefs(FTypeTrait ftt, Set<String> fields) {
//        BetterEnv into = ftt.getMembers();
//        BetterEnv forTraitMethods = ftt.getMethodExecutionEnv();
//        List<Decl> defs = ftt.getASTmembers();
//
//        BuildTraitEnvironment inner = new BuildTraitEnvironment(into,
//                forTraitMethods, ftt, fields);
//
//        inner.doDefs1234(defs);
//
//    }
//
    public void doDefs1234(List<Decl> defs) {
        doDefs(defs);
        doDefs234(defs);
    }

    public void doDefs234(List<Decl> defs) {
        secondPass();
        doDefs(defs);
        thirdPass();
        doDefs(defs);
        fourthPass();
        doDefs(defs);
    }

    protected void guardedPutValue(Environment e, String name, FValue value,
            HasAt where) {
        guardedPutValue(e, name, value, null, where);

    }

    /**
     * Put a value, perhaps unconditionally depending on subtype's choice
     * @param e
     * @param name
     * @param value
     * @param ft
     */
    protected void putValue(Environment e, String name, FValue value, FType ft) {
        e.putVariable(name, value, ft);
    }

    /**
     * Put a value, perhaps unconditionally depending on subtype's choice
     */
    protected void putValue(Environment e, String name, FValue value) {
        e.putValue(name, value);
    }

    protected void guardedPutValue(Environment e, String name, FValue value,
            FType ft, HasAt where) {
        try {
            if (ft != null) {
                if (!ft.typeMatch(value)) {
                    error(where, e,
                            errorMsg("Type mismatch binding ", value, " (type ",
                                     value.type(), ") to ", name, " (type ",
                                     ft, ")"));
                }
                putValue(e, name, value, ft);
            } else {
                putValue(e, name, value);
            }
        } catch (FortressException pe) {
            throw pe.setContext(where,e);
        }
    }

    protected void guardedPutType(String name, FType type, HasAt where) {
        EvalType.guardedPutType(name, type, where, containing);
    }

    protected FValue newGenericClosure(Environment e, FnDecl x) {
        return new FGenericFunction(e, x);
    }



    private void forFnDecl1(FnDecl x) {
        List<StaticParam> optStaticParams = x.getStaticParams();
        String fname = NodeUtil.nameAsMethod(x);
        FValue cl;

        if (!optStaticParams.isEmpty()) {
            cl = newGenericClosure(containing, x);
        } else {
            // NOT GENERIC
            cl = newClosure(containing, x);
        }
        // TODO this isn't right if it was a test function.
        // it belongs in a different namespace if it is.
        bindInto.putValue(fname, cl); // was "shadow"
        //LINKER putOrOverloadOrShadowGeneric(x, containing, name, cl);
    }

   private void forFnDecl2(FnDecl x) {
   }

   // Overridden in BuildTraitEnvironment
   protected void forFnDecl3(FnDecl x) {
       List<StaticParam> optStaticParams = x.getStaticParams();
       String fname = NodeUtil.nameAsMethod(x);
       Fcn fcn = (Fcn)containing.getLeafValue(fname);
       fcn.finishInitializing();
   }

   private void forFnDecl4(FnDecl x) {
   }

 /*
     * (non-Javadoc)
     *
     * @see com.sun.fortress.interpreter.nodes.NodeVisitor#forFnDecl(com.sun.fortress.interpreter.nodes.FnDecl)
     */
    @Override
    public Boolean forFnDecl(FnDecl x) {
        if (x.getBody().isNone())
            bug("Function definition should have a body expression.");

        switch (getPass()) {
        case 1: forFnDecl1(x); break;
        case 2: forFnDecl2(x); break;
        case 3: forFnDecl3(x); break;
        case 4: forFnDecl4(x); break;
        }
       return null;
    }

//    public void putOrOverloadOrShadow(HasAt x, BetterEnv e, IdOrOpOrAnonymousName name,
//            Simple_fcn cl) {
//        Fcn g = (Fcn) e.getValueNull(name.name());
//        if (g == null) {
//            putFunction(e, name, cl, x);
//
//            // This is delicate temporary code (below), and breaks the
//            // property that adding another layer of environment is an OK
//            // thing to do.
//        } else if (g.getWithin().equals(e)) {
//            // OVERLOADING
//            OverloadedFunction og;
//            if (g instanceof OverloadedFunction) {
//                og = (OverloadedFunction) g;
//                og.addOverload(cl);
//            } else if (g instanceof GenericMethodSet
//                    || g instanceof GenericMethod) {
//                error(x, e,
//                        "Cannot combine generic method and nongeneric method "
//                                + name.name() + " in an overloading");
//            } else if (g instanceof GenericFunctionSet
//                    || g instanceof FGenericFunction) {
//                error(x, e,
//                        "Cannot combine generic function and nongeneric function "
//                                + name.name() + " in an overloading");
//            } else {
//                og = new OverloadedFunction(name, e);
//                og.addOverload(cl);
//                og.addOverload((Simple_fcn) g);
//
//                assignFunction(e, name, og);
//            }
//        } else {
//            // SHADOWING
//            putFunction(e, name, cl, x);
//        }
//    }

//    /**
//     * @param x
//     * @param e
//     * @param name
//     * @param cl
//     */
//    private void putOrOverloadOrShadowGeneric(HasAt x, BetterEnv e,
//            IdOrOpOrAnonymousName name, FValue cl) {
//        FValue fv = e.getValueNull(name.name());
//        if (fv != null && !(fv instanceof Fcn)) {
//            error(x, e, "Generic not generic? " + name.name());
//        }
//        Fcn g = (Fcn) fv;
//        // Actually need to test for diff types of g.
//        if (g == null) {
//            putFunction(e, name, cl, x);
//        } else if (g.getWithin().equals(e)) {
//            // OVERLOADING
//            if (cl instanceof GenericMethod) {
//                GenericMethod clg = (GenericMethod) cl;
//                GenericMethodSet og;
//                if (g instanceof GenericMethodSet) {
//                    og = (GenericMethodSet) g;
//                    og.addOverload(clg);
//                } else if (g instanceof GenericMethod) {
//                    og = new GenericMethodSet(name, e);
//                    og.addOverload(clg);
//                    og.addOverload((GenericMethod) g);
//
//                    assignFunction(e, name, og);
//                } else {
//                    error(x, e, "Overload of generic method "
//                            + cl + " with non-generic/method " + g);
//                }
//            } else if (cl instanceof FGenericFunction) {
//                FGenericFunction clg = (FGenericFunction) cl;
//                GenericFunctionSet og;
//                if (g instanceof GenericFunctionSet) {
//                    og = (GenericFunctionSet) g;
//                    og.addOverload(clg);
//                } else if (g instanceof FGenericFunction) {
//                    og = new GenericFunctionSet(name, e);
//                    og.addOverload(clg);
//                    og.addOverload((FGenericFunction) g);
//
//                    assignFunction(e, name, og);
//                } else {
//                    error(x, e, "Overload of function method "
//                            + cl + " with non-generic/method " + g);
//                }
//            } else {
//                error(x, e,
//                        "Overload of generic, but not a method/function" + cl
//                                + " with generic/method " + g);
//
//            }
//        } else {
//            // SHADOWING
//            putFunction(e, name, cl, x);
//        }
//    }

    protected Simple_fcn newClosure(Environment e, Applicable x) {
        return new Closure(e, x);
    }

    private void putFunction(Environment e, IdOrOpOrAnonymousName name, FValue f, HasAt x) {
        String s = NodeUtil.nameString(name);
        guardedPutValue(e, s, f, x);
        e.noteName(s);
    }

    private static void assignFunction(Environment e, IdOrOpOrAnonymousName name, FValue f) {
        e.putValueRaw(NodeUtil.nameString(name), f);
    }

    /*
     * (non-Javadoc)
     *
     * @see com.sun.fortress.interpreter.nodes.NodeVisitor#forObjectDef(com.sun.fortress.interpreter.nodes.ObjectDecl)
     */
    @Override
    public Boolean forObjectDecl(ObjectDecl x) {
        switch (getPass()) {
        case 1: forObjectDecl1(x); break;
        case 2: forObjectDecl2(x); break;
        case 3: forObjectDecl3(x); break;
        case 4: forObjectDecl4(x); break;
        }
       return null;
    }
    protected void forObjectDecl1(ObjectDecl x) {
        // List<Modifier> mods;

        Environment e = containing;
        Id name = x.getName();

        List<StaticParam> staticParams = x.getStaticParams();
        Option<List<Param>> params = x.getParams();

        // List<Type> throws_;
        // List<WhereClause> where;
        // Contract contract;
        // List<Decl> defs = x.getDecls();
        String fname = NodeUtil.nameString(name);
        FTraitOrObjectOrGeneric ft;
        ft = staticParams.isEmpty() ?
                  new FTypeObject(fname, e, x, params, x.getDecls(), x)
                : new FTypeGeneric(e, x, x.getDecls(), x);

        // Need to check for overloaded constructor.

        guardedPutType(fname, ft, x);

        if (params.isSome()) {
            if (!staticParams.isEmpty()) {
                // A generic, not yet a constructor
                GenericConstructor gen = new GenericConstructor(e, x, name);
                guardedPutValue(containing, fname, gen, x);
            } else {
                // TODO need to deal with constructor overloading.

                // If parameters are present, it is really a constructor
                // BetterEnv interior = new SpineEnv(e, x);
                Constructor cl = new Constructor(containing, (FTypeObject) ft,
                        x);
                guardedPutValue(containing, fname, cl, x);
                // doDefs(interior, defs);
            }

        } else {
            if (!staticParams.isEmpty()) {
                // A parameterized singleton is a sort of generic value.
                // bug(x,"Generic singleton objects not yet implemented");
                makeGenericSingleton(x, e, name, fname, ft);

            } else {
                // It is a singleton; do not expose the constructor, do
                // visit the interior environment.
                // BetterEnv interior = new SpineEnv(e, x);

                // TODO - binding into "containing", or "bindInto"?

                Constructor cl = new Constructor(containing, (FTypeObject) ft,
                        x);
                guardedPutValue(containing, WellKnownNames.obfuscatedSingletonConstructorName(fname, x), cl, x);

                // Create a little expression to run the constructor.
                Expr init = ExprFactory.makeTightJuxt(x.getSpan(),
                      ExprFactory.makeVarRef(x.getSpan(), WellKnownNames.obfuscatedSingletonConstructorName(fname, x), 0),
                      ExprFactory.makeVoidLiteralExpr(x.getSpan()));
                FValue init_value = new LazilyEvaluatedCell(init, containing);
                putValue(bindInto, fname, init_value);

                // doDefs(interior, defs);
            }
        }

        scanForFunctionalMethodNames(ft, x.getDecls());

    }

    private void makeGenericSingleton(ObjectDecl x, Environment e, Id name,
            String fname, FTraitOrObjectOrGeneric ft) {
        GenericConstructor gen = new GenericConstructor(e, x, name);
        guardedPutValue(containing, WellKnownNames.obfuscatedSingletonConstructorName(fname, x), gen, x);
        guardedPutValue(containing, fname, new GenericSingleton(x,ft, gen), x);
    }

    public void scanForFunctionalMethodNames(
            FTraitOrObjectOrGeneric x,
            List<Decl> defs) {
        scanForFunctionalMethodNames(x, defs, false);
    }

    public void scanForFunctionalMethodNames(FTraitOrObjectOrGeneric x,
            List<Decl> defs, boolean bogus) {
        // This is probably going away.
        Environment topLevel = containing;
        if (getPass() == 1) {
            x.initializeFunctionalMethods();
        } else if (getPass() == 3) {
            x.finishFunctionalMethods();
        }

    }


     private void forObjectDecl2(ObjectDecl x) {

        Environment e = containing;
        Id name = x.getName();

        List<StaticParam> staticParams = x.getStaticParams();
        Option<List<Param>> params = x.getParams();

        String fname = NodeUtil.nameString(name);
        FType ft;

        if (params.isSome()) {
            if (!staticParams.isEmpty()) {
                // Do nothing.
            } else {
                FTypeObject fto = (FTypeObject) containing.getRootType(fname); // top level
                FValue xxx = containing.getLeafValue(fname);
                //Constructor cl = (Constructor) containing.getValue(fname);
                finishObjectTrait(x, fto);
            }
        } else {
            // If there are no parameters, it is a singleton.
            // Not clear we can evaluate it yet.
            if (!staticParams.isEmpty()) {
                // Do nothing.
            } else {
                FTypeObject fto = (FTypeObject) containing.getRootType(fname); // top level

                finishObjectTrait(x, fto);
            }

        }

    }
    private void forObjectDecl3(ObjectDecl x) {
        Environment e = containing;
        Id name = x.getName();

        List<StaticParam> staticParams = x.getStaticParams();
        Option<List<Param>> params = x.getParams();

        String fname = NodeUtil.nameString(name);
        FTraitOrObjectOrGeneric ft = (FTraitOrObjectOrGeneric) containing.getRootType(fname); // toplevel
        if (!staticParams.isEmpty()) {
            // Do nothing
        } else if (params.isSome()) {
            FTypeObject fto = (FTypeObject) ft;
            Fcn cl = (Fcn) containing.getLeafValue(fname);
//                List<Parameter> fparams = EvalType.paramsToParameters(
//                        containing, params.unwrap());
//                cl.setParams(fparams);
            cl.finishInitializing();
        } else {
            Constructor cl = (Constructor) containing
                .getLeafValue(WellKnownNames.obfuscatedSingletonConstructorName(fname, x));
            //  cl.setParams(Collections.<Parameter> emptyList());
            cl.finishInitializing();
        }
        scanForFunctionalMethodNames(ft, x.getDecls());
    }
    private void forObjectDecl4(ObjectDecl x) {

        Environment e = containing;
        Id name = x.getName();

        Option<List<Param>> params = x.getParams();

        String fname = NodeUtil.nameString(name);

        if (params.isSome()) {

        } else {
            // TODO - Blindly assuming a non-generic singleton.
            // TODO - Need to insert the name much, much, earlier; this is too late.

            FValue value = bindInto.getLeafValue(fname);

//            Constructor cl = (Constructor) containing
//                    .getValue(obfuscated(fname));
//
//            guardedPutValue(containing, fname, cl.apply(java.util.Collections
//                    .<FValue> emptyList(), x, e), x);

        }
    }


    private String obfuscatedConstructorName(String fname) {
        // TODO Auto-generated method stub
        return "*1_" + fname;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.sun.fortress.interpreter.nodes.NodeVisitor#forVarDef(com.sun.fortress.interpreter.nodes.VarDecl)
     */
    @Override
    public Boolean forVarDecl(VarDecl x) {
        switch (getPass()) {
        case 1:
            forVarDecl1(x);
            break;
        case 2:
            forVarDecl2(x);
            break;
        case 3:
            forVarDecl3(x);
            break;
        case 4:
            forVarDecl4(x);
            break;
        }
        return null;
    }

    private void forVarDecl1(VarDecl x) {
        List<LValue> lhs = x.getLhs();

        // List<Modifier> mods;
        // Id name = x.getName();
        // Option<Type> type = x.getType();

        if (x.getInit().isNone())
            bug("Variable definition should have an expression.");

        Expr init = x.getInit().unwrap();
        LValue lvb = lhs.get(0);

          Option<Type> type = lvb.getType();
          Id name = lvb.getName();
          String sname = NodeUtil.nameString(name);

          try {
              /* Ignore the type, until later */
              if (lvb.isMutable()) {
                  bindInto.putVariablePlaceholder(sname);
              } else {
                  FValue init_val = new LazilyEvaluatedCell(init, containing);
                  putValue(bindInto, sname, init_val);
              }
          } catch (FortressException pe) {
              throw pe.setContext(x,bindInto);
          }

//        int index = 0;

//        for (LValue lv : lhs) {
//            if (lv instanceof LValue) {
//                LValue lvb = (LValue) lv;
//                Option<Type> type = lvb.getType();
//                Id name = lvb.getName();
//                String sname = name.getName();
//
//                try {
//                    /* Ignore the type, until later */
//                    if (lvb.isMutable()) {
//                        bindInto.putVariablePlaceholder(sname);
//                    } else {
//                        FValue init_val;
//                        if (init instanceof ArgExpr) {
//                            init_val = new LazilyEvaluatedCell(
//                                      ((ArgExpr)init).getExprs().get(index++),
//                                      containing);
//                        } else {
//                            init_val = new LazilyEvaluatedCell(init, containing);
//                        }
//                        putValue(bindInto, sname, init_val);
//                    }
//                } catch (FortressError pe) {
//                    throw pe.setContext(x,bindInto);
//                }
//
//            } else {
//                bug(x, "Don't support arbitary LHS in Var decl yet");
//            }
//        }
    }

    private void forVarDecl2(VarDecl x) {

    }

    private void forVarDecl3(VarDecl x) {


    }

    private void forVarDecl4(VarDecl x) {

        List<LValue> lhs = x.getLhs();

        // List<Modifier> mods;
        // Id name = x.getName();
        // Option<Type> type = x.getType();
        if (x.getInit().isNone())
            bug("Variable definition should have an expression.");

        Expr init = x.getInit().unwrap();
        // int index = 0;
        LValue lvb = lhs.get(0);


         {
                Option<Type> type = lvb.getType();
                Id name = lvb.getName();
                String sname = NodeUtil.nameString(name);

                FType ft = type.isSome() ?
                        (new EvalType(containing)).evalType(type.unwrap())
                                : null;

                if (lvb.isMutable()) {
                    Expr rhs = init;

                    FValue value = (new Evaluator(containing)).eval(rhs);

                    // TODO When new environment are created, need to insert
                    // into containing AND bindInto

                    if (ft != null) {
                        if (!ft.typeMatch(value)) {
                            ft = error(x, bindInto,
                                    errorMsg("Type mismatch binding ", value, " (type ",
                                             value.type(), ") to ", name, " (type ",
                                             ft, ")"));
                        }
                    } else {
                        ft = FTypeTop.ONLY;
                    }
                    /* Finally, can finish this initialiation. */
                    bindInto.storeType(x, sname, ft);
                    bindInto.assignValue(x, sname, value);
                } else {
                    // Force evaluation, snap the link, check the type!
                    FValue value = bindInto.getLeafValue(sname);
                    if (ft != null) {
                        if (!ft.typeMatch(value)) {
                            error(x, bindInto,
                                  errorMsg("Type mismatch binding ", value, " (type ",
                                  value.type(), ") to ", name, " (type ",
                                  ft, ")"));
                        }
                    }
                }
        }

    }

    /*
     * (non-Javadoc)
     *
     * @see com.sun.fortress.interpreter.nodes.NodeVisitor#forTraitDef(com.sun.fortress.interpreter.nodes.TraitDecl)
     */
    @Override
    public Boolean forTraitDecl(TraitDecl x) {
        switch (getPass()) {
        case 1: forTraitDecl1(x); break;
        case 2: forTraitDecl2(x); break;
        case 3: forTraitDecl3(x); break;
        case 4: forTraitDecl4(x); break;
        }
       return null;
    }
    private void forTraitDecl1(TraitDecl x) {
        // TODO Auto-generated method stub
        List<StaticParam> staticParams = x.getStaticParams();
        // List<Modifier> mods;
        Id name = x.getName();
        // List<Type> excludes;
        // Option<List<Type>> bounds;
        // List<WhereClause> where;
        FTraitOrObjectOrGeneric ft;

        String fname = NodeUtil.nameString(name);

        if (!staticParams.isEmpty()) {

            FTypeGeneric ftg = new FTypeGeneric(containing, x, x.getDecls(), x);
            guardedPutType(fname, ftg, x);
            //scanForFunctionalMethodNames(ftg, x.getDecls(), ftg);
           ft = ftg;
        } else {

            Environment interior = containing; // new BetterEnv(containing, x);
            FTypeTrait ftt = new FTypeTrait(fname, interior, x, x.getDecls(), x);
            guardedPutType(fname, ftt, x);
            //scanForFunctionalMethodNames(ftt, x.getDecls(), ftt);
            ft = ftt;
        }

        scanForFunctionalMethodNames(ft, x.getDecls());
    }
    private void forTraitDecl2(TraitDecl x) {
        // TODO Auto-generated method stub
        List<StaticParam> staticParams = x.getStaticParams();
        // List<Modifier> mods;
        // List<Type> excludes;
        // Option<List<Type>> bounds;
        // List<WhereClause> where;

        if (staticParams.isEmpty()) {
            Id name = x.getName();
            FTypeTrait ftt =
                (FTypeTrait) containing.getRootType(NodeUtil.nameString(name)); // toplevel
            Environment interior = ftt.getWithin();
            finishTrait(x, ftt, interior);
        }
    }
    private void forTraitDecl3(TraitDecl x) {
        Id name = x.getName();
        String fname = NodeUtil.nameString(name);
        FTraitOrObjectOrGeneric ft =  (FTraitOrObjectOrGeneric) containing.getRootType(fname); // toplevel
        scanForFunctionalMethodNames(ft, x.getDecls());
    }

    private void forTraitDecl4(TraitDecl x) {
    }

    /**
     * @param x
     * @param ftt
     * @param interior
     */
    public void finishTrait(TraitDecl x, FTypeTrait ftt, Environment interior) {
        List<BaseType> extends_ = NodeUtil.getTypes(x.getExtendsClause());
        // TODO What if I don't
        // interior = interior.extendAt(x);

        EvalType et;
        if ( x.getWhere().isSome() )
            et = processWhereClauses(x.getWhere().unwrap(), interior);
        else
            et = new EvalType(interior);

        List<FType> extl = et.getFTypeListFromList(extends_);
        List<FType> excl = et.getFTypeListFromList(x.getExcludes());
        ftt.setExtendsAndExcludes(extl, excl, interior);
        Option<List<BaseType>> comprs = x.getComprises();
        if (!comprs.isNone()) {
            List<FType> c = et.getFTypeListFromList(comprs.unwrap());
            ftt.setComprises(Useful.<FType>set(c));
        }
        List<Decl> fns = x.getDecls();

        // doTraitMethodDefs(ftt, null); /* NOTICE THE DIFFERENT ENVIRONMENT! */

    }


    /**
     * Processes a where clause,
     * both using and augmenting the environment
     * "interior" passed in as a parameter.
     *
     * @param wheres
     * @param interior
     * @return
     */
    private static EvalType processWhereClauses(WhereClause wheres,
                                                Environment interior) {

        if (wheres != null) {
            for (WhereConstraint w : wheres.getConstraints()) {
                if (w instanceof WhereExtends) {
                    WhereExtends we = (WhereExtends) w;
                    Id name = we.getName();
                    String string_name = NodeUtil.nameString(name);
                    // List<Type> types = we.getSupers();
                    FType ft = interior.getLeafTypeNull(string_name); // leaf
                    if (ft == null) {
                        ft = new SymbolicWhereType(string_name, interior, we);
                        interior.putType(string_name, ft);
                    }
                } else {
                    bug(w, errorMsg("Where clause ", w));
                }
            }
        }

        EvalType et = new EvalType(interior);

        if (wheres != null) {
            for (WhereConstraint w : wheres.getConstraints()) {
                if (w instanceof WhereExtends) {
                    WhereExtends we = (WhereExtends) w;
                    Id name = we.getName();
                    String string_name = NodeUtil.nameString(name);
                    List<BaseType> types = we.getSupers();
                    FType ft = interior.getLeafTypeNull(string_name); // leaf
                    for (Type t : types) {
                        FType st = et.evalType(t); // t.visit(et);
                        if (ft instanceof SymbolicType) {
                            // Treat as "extends".
                            ((SymbolicType) ft).addExtend(st);
                        } else if (st instanceof SymbolicWhereType) {
                            // Record subtype ft of st.
                            SymbolicWhereType swt = (SymbolicWhereType) st;
                            swt.addSubtype(ft);
                        } else {
                            ft.mustExtend(st, w);
                            // Check that constraint holds.
                            // NI.nyi("need to verify constraint stated in where clause");
                        }
                    }
                } else {
                    bug(w, errorMsg("Where clause ", w));
                }
            }
        }
        return et;
    }

    public void finishObjectTrait(ObjectDecl x, FTypeObject ftt) {
        List<BaseType> extends_ = NodeUtil.getTypes(x.getExtendsClause());
        finishObjectTrait(extends_, null, x.getWhere(), ftt, containing, x);
    }

    public void finishObjectTrait(_RewriteObjectExpr x, FTypeObject ftt) {
        List<BaseType> extends_ = NodeUtil.getTypes(x.getExtendsClause());
        // _RewriteObjectExpr has no excludes clause.
        finishObjectTrait(extends_, null, null, ftt, containing, x);
    }

    static public void finishObjectTrait(List<BaseType> extends_,
                                         List<? extends Type> excludes,
                                         Option<WhereClause> wheres,
                                         FTypeObject ftt,
                                         Environment interior,
                                         HasAt x) {
        interior = interior.extendAt(x);
        EvalType et;
        if ( wheres != null && wheres.isSome() )
            et = processWhereClauses(wheres.unwrap(), interior);
        else
            et = new EvalType(interior);
        ftt.setExtendsAndExcludes(et.getFTypeListFromList(extends_), et
                .getFTypeListFromList(excludes), interior);

    }

    /*
     * (non-Javadoc)
     *
     * @see com.sun.fortress.interpreter.nodes.NodeVisitor#forTypeAlias(com.sun.fortress.interpreter.nodes.TypeAlias)
     */
    @Override
    public Boolean forTypeAlias(TypeAlias x) {
        // Id name;
        // List<Id> params;
        // Type type;
        // TODO Auto-generated method stub
        return null;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.sun.fortress.interpreter.nodes.NodeVisitor#forDimUnitDecl(com.sun.fortress.interpreter.nodes.DimUnitDecl)
     */
    @Override
    public Boolean forDimUnitDecl(DimUnitDecl x) {
        // TODO Auto-generated method stub

        return null;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.sun.fortress.interpreter.nodes.NodeVisitor#forDimArg(com.sun.fortress.interpreter.nodes.DimArg)
     */
    @Override
    public Boolean forDimArg(DimArg x) {
        // TODO Auto-generated method stub
        return null;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.sun.fortress.interpreter.nodes.NodeVisitor#forImportApi(com.sun.fortress.interpreter.nodes.ImportApi)
     */
    @Override
    public Boolean forImportApi(ImportApi x) {
        // TODO Auto-generated method stub
        return null;
    }



    /*
     * (non-Javadoc)
     *
     * @see com.sun.fortress.interpreter.nodes.NodeVisitor#forImportNames(com.sun.fortress.interpreter.nodes.ImportNames)
     */
    @Override
    public Boolean forImportNames(ImportNames x) {
        // TODO Auto-generated method stub
        return null;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.sun.fortress.interpreter.nodes.NodeVisitor#forImportStar(com.sun.fortress.interpreter.nodes.ImportStar)
     */
    @Override
    public Boolean forImportStar(ImportStar x) {
        // TODO Auto-generated method stub
        return null;
    }

    public Environment getBindingEnv() {
        return bindInto;
    }

    @Override
    public Boolean forGrammarDecl(GrammarDecl that) {
        return null; // Do nothing
    }

    @Override
    public Boolean forGrammarDef(GrammarDef that) {
        return null; // Do nothing
    }

    public void setPass(int pass) {
        this.pass = pass;
    }

    public int getPass() {
        return pass;
    }



}
