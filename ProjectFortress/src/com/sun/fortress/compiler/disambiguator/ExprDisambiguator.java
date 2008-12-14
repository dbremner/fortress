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

package com.sun.fortress.compiler.disambiguator;

import static edu.rice.cs.plt.tuple.Option.wrap;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import com.sun.fortress.compiler.index.TraitIndex;
import com.sun.fortress.compiler.index.TypeConsIndex;
import com.sun.fortress.exceptions.StaticError;
import com.sun.fortress.nodes.Decl;
import com.sun.fortress.nodes.TraitTypeWhere;
import com.sun.fortress.nodes.TraitTypeHeader;
import com.sun.fortress.nodes.WhereClause;
import com.sun.fortress.nodes.BaseType;
import com.sun.fortress.nodes.Contract;
import com.sun.fortress.nodes.APIName;
import com.sun.fortress.nodes.Decl;
import com.sun.fortress.nodes.ObjectDecl;
import com.sun.fortress.nodes.VarDecl;
import com.sun.fortress.nodes.Accumulator;
import com.sun.fortress.nodes.BaseType;
import com.sun.fortress.nodes.Block;
import com.sun.fortress.nodes.Catch;
import com.sun.fortress.nodes.Component;
import com.sun.fortress.nodes.Contract;
import com.sun.fortress.nodes.DimDecl;
import com.sun.fortress.nodes.Do;
import com.sun.fortress.nodes.Exit;
import com.sun.fortress.nodes.Expr;
import com.sun.fortress.nodes.FnDecl;
import com.sun.fortress.nodes.FnExpr;
import com.sun.fortress.nodes.FnRef;
import com.sun.fortress.nodes.FunctionalRef;
import com.sun.fortress.nodes.For;
import com.sun.fortress.nodes.GeneratorClause;
import com.sun.fortress.nodes.GrammarDecl;
import com.sun.fortress.nodes.Id;
import com.sun.fortress.nodes.IdOrOp;
import com.sun.fortress.nodes.IdOrOpOrAnonymousName;
import com.sun.fortress.nodes.KindDim;
import com.sun.fortress.nodes.IfClause;
import com.sun.fortress.nodes.LValue;
import com.sun.fortress.nodes.Label;
import com.sun.fortress.nodes.LetFn;
import com.sun.fortress.nodes.LocalVarDecl;
import com.sun.fortress.nodes.Node;
import com.sun.fortress.nodes.NodeDepthFirstVisitor;
import com.sun.fortress.nodes.NodeUpdateVisitor;
import com.sun.fortress.nodes.ObjectDecl;
import com.sun.fortress.nodes.ObjectExpr;
import com.sun.fortress.nodes.OpExpr;
import com.sun.fortress.nodes.Op;
import com.sun.fortress.nodes.OpRef;
import com.sun.fortress.nodes.Param;
import com.sun.fortress.nodes.StaticArg;
import com.sun.fortress.nodes.StaticParam;
import com.sun.fortress.nodes.TaggedDimType;
import com.sun.fortress.nodes.TaggedUnitType;
import com.sun.fortress.nodes.TraitDecl;
import com.sun.fortress.nodes.TraitType;
import com.sun.fortress.nodes.TraitTypeWhere;
import com.sun.fortress.nodes.Type;
import com.sun.fortress.nodes.Typecase;
import com.sun.fortress.nodes.UnitDecl;
import com.sun.fortress.nodes.VarRef;
import com.sun.fortress.nodes.VarType;
import com.sun.fortress.nodes.VoidLiteralExpr;
import com.sun.fortress.nodes.While;
import com.sun.fortress.nodes_util.ExprFactory;
import com.sun.fortress.nodes_util.Modifiers;
import com.sun.fortress.nodes_util.NodeFactory;
import com.sun.fortress.nodes_util.NodeUtil;
import com.sun.fortress.nodes_util.Span;
import com.sun.fortress.parser_util.FortressUtil;
import com.sun.fortress.useful.HasAt;
import com.sun.fortress.useful.Useful;

import edu.rice.cs.plt.collect.CollectUtil;
import edu.rice.cs.plt.collect.ConsList;
import edu.rice.cs.plt.iter.IterUtil;
import edu.rice.cs.plt.lambda.Lambda2;
import edu.rice.cs.plt.tuple.Option;
import edu.rice.cs.plt.tuple.Pair;
import edu.rice.cs.plt.tuple.Triple;

/**
 * <p>Eliminates ambiguities in an AST that can be resolved solely by knowing what kind
 * of entity a name refers to.  This class assumes all types in declarations have been
 * resolved, and specifically handles the following:
 * <ul>
 * <li>All names referring to APIs are made fully qualified (FnRefs and OpExprs may then
 *     contain lists of qualified names referring to multiple APIs).
 * <li>VarRefs referring to functions become FnRefs with placeholders for implicit static
 *     arguments filled in (to be replaced later during type inference).</li>
 * <li>VarRefs referring to trait members, and that are juxtaposed with Exprs, become
 *     MethodInvocations.  (Maybe?  Depends on parsing rules for getters.)</li>
 * <li>VarRefs referring to trait members become FieldRefs.</li>
 * <li>FieldRefs referring to trait members, and that are juxtaposed with Exprs, become
 *     MethodInvocations.  (Maybe?  Depends on parsing rules for getters.)</li>
 * <li>FnRefs referring to trait members, and that are juxtaposed with Exprs, become
 *     MethodInvocations.</li>
 * <li>TODO: StaticArgs of FnRefs, and types nested within them, are disambiguated.</li>
 * </ul>
 *
 * Additionally, all name references that are undefined or used incorrectly are
 * treated as static errors.  (TODO: check names in non-type static args)</p>
 */
public class ExprDisambiguator extends NodeUpdateVisitor {

    private NameEnv _env;
    private Set<Id> _uninitializedNames;
    private List<StaticError> _errors;
    private Option<Id> _innerMostLabel;
    private boolean inComponent = false;

    public ExprDisambiguator(NameEnv env, List<StaticError> errors) {
        _env = env;
        _uninitializedNames = Collections.emptySet();
        _errors = errors;
        _innerMostLabel = Option.<Id>none();
    }

    private ExprDisambiguator(NameEnv env, List<StaticError> errors, Option<Id> innerMostLabel) {
        this(env, errors);
        _innerMostLabel = innerMostLabel;
    }

    private ExprDisambiguator(NameEnv env, Set<Id> uninitializedNames, List<StaticError> errors, Option<Id> innerMostLabel) {
        this(env, errors, innerMostLabel);
        _uninitializedNames = uninitializedNames;
    }

    /**
     * Check that the variable corresponding to the give Id does not shadow any variables or
     * functions in scope.
     */
    private void checkForShadowingVar(Id var) {
        if (! var.getText().equals("self") && ! var.getText().equals("_") &&
            ! var.getText().equals("outcome") &&
            ! _uninitializedNames.contains(var)) {
            if( !_env.explicitVariableNames(var).isEmpty() ||
                !_env.explicitFunctionNames(var).isEmpty() ||
                !_env.explicitTypeConsNames(var).isEmpty()) {
                error("Variable " + var + " is already declared.", var);
            }
        }
    }

    /**
     * Check that the local function corresponding to the given Id does not shadow any variables
     * or non-overloading functions in scope.
     */
    private void checkForShadowingLocalFunction(Id var, Set<Id> allowedShadowings) {
        if ( (!_env.explicitVariableNames(var).isEmpty() ||
              !_env.explicitFunctionNames(var).isEmpty() ||
              !_env.explicitTypeConsNames(var).isEmpty())
             && !allowedShadowings.contains(var)) {
            error("Variable " + var + " is already declared.", var);
        }
    }

    /**
     * Check that the function corresponding to the given Id does not shadow any variables
     * in scope.
     */
    private void checkForShadowingFunction(Id var, Set<Id> allowedShadowings) {
        if ( !_env.explicitVariableNames(var).isEmpty() && !allowedShadowings.contains(var)) {
            error("Variable " + var + " is already declared.", var);
        }
    }

    private void checkForShadowingVars(Set<Id> vars) {
        // First check that vars do not shadow other declarations in scope
        for (Id var : vars) { checkForShadowingVar(var); }

        // Now check that these vars do not conflict. We could speed up asymptotic complexity by sorting first.
        // But vars is expected to be relatively small, so the overhead of sorting probably isn't worth it.

        // A single var has nothing to conflict with.
        if (vars.size() > 1) {
            Object[] _vars = vars.toArray();
            for (int i = 0; i < _vars.length - 1; i++) {
                for (int j = i + 1; j < _vars.length; j++) {
                    if (_vars[i].equals(_vars[j])) {
                        error("Variable " + _vars[i] + " is already declared at " + ((Id)_vars[j]).getSpan(), (Id)_vars[i]);
                    }
                }
            }
        }
    }

    private void checkForShadowingLocalFunctions(Set<? extends IdOrOpOrAnonymousName> definedNames) {
        for (IdOrOpOrAnonymousName name : definedNames) {
            if (name instanceof Id) {
                checkForShadowingLocalFunction((Id)name, Collections.<Id>emptySet());
            }
        }
    }

    private void checkForShadowingLocalFunctions(Set<? extends IdOrOpOrAnonymousName> definedNames,
                                            Set<Id> allowedShadowings) {
        for (IdOrOpOrAnonymousName name : definedNames) {
            if (name instanceof Id) {
                checkForShadowingLocalFunction((Id)name, allowedShadowings);
            }
        }
    }

    private void checkForShadowingFunctions(Set<? extends IdOrOpOrAnonymousName> definedNames) {
        for (IdOrOpOrAnonymousName name : definedNames) {
            if (name instanceof Id) {
                checkForShadowingFunction((Id)name, Collections.<Id>emptySet());
            }
        }
    }

    private void checkForShadowingFunctions(Set<? extends IdOrOpOrAnonymousName> definedNames,
                                            Set<Id> allowedShadowings) {
        for (IdOrOpOrAnonymousName name : definedNames) {
            if (name instanceof Id) {
                checkForShadowingFunction((Id)name, allowedShadowings);
            }
        }
    }

    private void checkForValidParams(Set<Id> params) {
        for (Id param : params) {
            if (param.getText().equals("outcome")) {
                error("Parameters must not be named `outcome'", param);
            }
        }
    }


    private ExprDisambiguator extendWithVars(Set<Id> vars) {
        checkForShadowingVars(vars);
        return extendWithVarsNoCheck(vars);
    }

    private ExprDisambiguator extendWithVars(Set<Id> vars, Set<Id> uninitializedNames) {
        checkForShadowingVars(vars);
        return extendWithVarsNoCheck(vars, uninitializedNames);
    }

    private ExprDisambiguator extendWithVarsNoCheck(Set<Id> vars) {
        NameEnv newEnv = new LocalVarEnv(_env, vars);
        return new ExprDisambiguator(newEnv, _uninitializedNames, _errors, this._innerMostLabel);
    }

    private ExprDisambiguator extendWithVarsNoCheck(Set<Id> vars, Set<Id> uninitializedNames) {
        NameEnv newEnv = new LocalVarEnv(_env, vars);
        uninitializedNames.addAll(_uninitializedNames);
        return new ExprDisambiguator(newEnv, uninitializedNames, _errors, this._innerMostLabel);
    }

    private ExprDisambiguator extendWithLocalFns(Set<? extends IdOrOpOrAnonymousName> definedNames) {
        checkForShadowingLocalFunctions(definedNames);
        return extendWithFnsNoCheck(definedNames);
    }

    private ExprDisambiguator extendWithLocalFns(Set<? extends IdOrOpOrAnonymousName> definedNames,
                                            Set<Id> allowedShadowings)
    {
        checkForShadowingLocalFunctions(definedNames, allowedShadowings);
        return extendWithFnsNoCheck(definedNames);
    }

    private ExprDisambiguator extendWithFns(Set<? extends IdOrOpOrAnonymousName> definedNames) {
        checkForShadowingFunctions(definedNames);
        return extendWithFnsNoCheck(definedNames);
    }

    private ExprDisambiguator extendWithFns(Set<? extends IdOrOpOrAnonymousName> definedNames,
                                            Set<Id> allowedShadowings)
    {
        checkForShadowingFunctions(definedNames, allowedShadowings);
        return extendWithFnsNoCheck(definedNames);
    }

    private ExprDisambiguator extendWithFnsNoCheck(Set<? extends IdOrOpOrAnonymousName> definedNames) {
        NameEnv newEnv = new LocalFnEnv(_env, CollectUtil.makeSet(IterUtil.relax(definedNames)));
        return new ExprDisambiguator(newEnv, _uninitializedNames, _errors, this._innerMostLabel);
    }

    private ExprDisambiguator extendWithSelf(Span span) {
        Set<Id> selfSet = Collections.singleton(NodeFactory.makeId(span, "self"));
        return extendWithVars(selfSet);
    }

    private ExprDisambiguator extendWithOutcome(Span span) {
        Set<Id> outcomeSet = Collections.singleton(NodeFactory.makeId(span, "outcome"));
        return extendWithVars(outcomeSet);
    }

    private void error(String msg, HasAt loc) {
        _errors.add(StaticError.make(msg, loc));
    }

    /** LocalVarDecls introduce local variables while visiting the body. */
    @Override public Node forLocalVarDecl(LocalVarDecl that) {
        List<LValue> lhsResult = recurOnListOfLValue(that.getLhs());
        Option<Expr> rhsResult = recurOnOptionOfExpr(that.getRhs());
        Set<Id> definedNames = extractDefinedVarNames(lhsResult);
        Set<Id> uninitializedNames = new HashSet<Id>();

        // Record uninitialized local variables so that:
        //   1. We can check that these variables are initialized before use.
        //   2. We don't signal shadowing errors when they are initialized.
        if (rhsResult.isNone()) {
            uninitializedNames = definedNames;
        }

        Option<Type> type_result = recurOnOptionOfType(that.getExprType());
        //NameEnv newEnv = new LocalVarEnv(_env, definedNames);
        ExprDisambiguator v = extendWithVars(definedNames, uninitializedNames);
        List<Expr> bodyResult = v.recurOnListOfExpr(that.getBody());
        return forLocalVarDeclOnly(that, type_result , bodyResult, lhsResult, rhsResult);
    }

    private Set<Id> extractDefinedVarNames(Iterable<? extends LValue> lvalues) {
        Set<Id> result = new HashSet<Id>();
        extractDefinedVarNames(lvalues, result);
        return result;
    }

    private void extractDefinedVarNames(Iterable<? extends LValue> lvalues,
                                        Set<Id> result) {
        for (LValue lv : lvalues) {
            boolean valid = true;
            Id id = lv.getName();
            valid = (result.add(id) || id.getText().equals("_"));
            if (!valid) { error("Duplicate local variable name", lv); }
        }
    }

    /**
     * Pull out all static variables that can be used in expression contexts,
     * and return them as a Set<Id>.
     * TODO: Collect OpParams as well.
     */
    private Set<Id> extractStaticExprVars(List<StaticParam> staticParams) {
        Set<Id> result = new HashSet<Id>();
        for (StaticParam staticParam: staticParams) {
            if ( ! (NodeUtil.isDimParam(staticParam)) &&
                 ! (NodeUtil.isOpParam(staticParam)) )
                result.add((Id)staticParam.getName());
        }
        return result;
    }

    /**
     * Convenience method that unwraps its argument and passes it
     * to the overloaded definition of extractParamNames on lists.
     */
    private Set<Id> extractParamNames(Option<List<Param>> params) {
        if (params.isNone()) { return new HashSet<Id>(); }
        else { return extractParamNames(params.unwrap()); }
    }

    /**
     * Returns a list of Ids of the given list of Params.
     */
    private Set<Id> extractParamNames(List<Param> params) {
        Set<Id> result = new HashSet<Id>();

        for (Param param: params) {
            result.add(param.getName());
        }
        return result;
    }

    private Triple<Set<Id>, Set<IdOrOpOrAnonymousName>, Set<IdOrOpOrAnonymousName>>
        extractDeclNames(List<Decl> decls) {
        final Set<IdOrOpOrAnonymousName> accessors = new HashSet<IdOrOpOrAnonymousName>();

        NodeDepthFirstVisitor<Set<Id>> var_finder = new NodeDepthFirstVisitor<Set<Id>>(){

            @Override
            public Set<Id> forVarDecl(VarDecl that) {
                return extractDefinedVarNames(that.getLhs());
            }

            @Override
            public Set<Id> forFnDecl(FnDecl that) {
                return Collections.emptySet();
            }

        };
        NodeDepthFirstVisitor<Set<IdOrOpOrAnonymousName>> fn_finder =
            new NodeDepthFirstVisitor<Set<IdOrOpOrAnonymousName>>() {

            @Override
            public Set<IdOrOpOrAnonymousName> forVarDecl(VarDecl that) {
                return Collections.emptySet();
            }

            @Override
            public Set<IdOrOpOrAnonymousName> forFnDecl(FnDecl that) {
                if (that.getMods().isGetterSetter()) {
                    accessors.add(that.getName());
                    return Collections.emptySet();
                } else if (FortressUtil.isFunctionalMethod(that.getParams())) {
                    // don't add functional methods! they go at the top level...
                    return Collections.emptySet();
                } else {
                    return Collections.singleton(that.getName());
                }
            }
        };

        Set<Id> vars = new HashSet<Id>();
        for (Decl decl : decls) {
            vars.addAll(decl.accept(var_finder));
        }

        Set<IdOrOpOrAnonymousName> fns = new HashSet<IdOrOpOrAnonymousName>();
        for (Decl decl : decls) {
            fns.addAll(decl.accept(fn_finder));
        }

        return Triple.make(vars, accessors, fns);
    }

//     private Pair<Set<Id>,Set<IdOrOpOrAnonymousName>> extractDeclNames(List<Decl> decls) {
//         final Set<IdOrOpOrAnonymousName> accessors = new HashSet<IdOrOpOrAnonymousName>();

//         NodeDepthFirstVisitor<Set<Id>> var_finder = new NodeDepthFirstVisitor<Set<Id>>(){
//             @Override
//             public Set<Id> forVarDecl(VarDecl that) {
//                 return extractDefinedVarNames(that.getLhs());
//             }

//             @Override
//             public Set<Id> forFnDecl(FnDecl that) {
//                 return Collections.emptySet();
//             }

//         };
//         NodeDepthFirstVisitor<Set<IdOrOpOrAnonymousName>> fn_finder = new NodeDepthFirstVisitor<Set<IdOrOpOrAnonymousName>>(){
//             @Override
//             public Set<IdOrOpOrAnonymousName> forVarDecl(VarDecl that) {
//                 return Collections.emptySet();
//             }

//             @Override
//             public Set<IdOrOpOrAnonymousName> forFnDecl(FnDecl that) {
//                 if( isSetterOrGetter(that.getMods()) )
//                     accessors.add(that.getName());
//                 if( FortressUtil.isFunctionalMethod(that.getParams()) ) {
//                     // don't add functional methods! they go at the top level...
//                     return Collections.emptySet();
//                 }
//                 else {
//                     return Collections.singleton(that.getName());
//                 }
//             }
//         };
//         List<Set<Id>> vars_ = var_finder.recurOnListOfDecl(decls);
//         List<Set<IdOrOpOrAnonymousName>> fns_ = fn_finder.recurOnListOfDecl(decls);

//         Set<Id> vars = Useful.union(vars_);
//         Set<IdOrOpOrAnonymousName> fns = new HashSet<IdOrOpOrAnonymousName>(Useful.union(fns_));

//         // For every accessor, remove that fn from fns if there is also a variable.
//         // See shadowing rules in section 7.3
//         for( IdOrOpOrAnonymousName ioooan : accessors ) {
//             if( vars.contains(ioooan) ) {
//                 fns.remove(ioooan);
//             }
//         }

//         return Pair.make(vars, fns);
//     }

    /**
     * When recurring on an ObjExpr, we first need to extend the
     * environment with all the newly bound variables and methods
     * TODO: Insert inherited method names into the environment.
     */
    @Override
	public Node forObjectExpr(ObjectExpr that) {
        List<TraitTypeWhere> extendsClause = recurOnListOfTraitTypeWhere(that.getExtendsClause());

        // Include trait declarations and inherited methods
        Triple<Set<Id>,Set<IdOrOpOrAnonymousName>, Set<IdOrOpOrAnonymousName>> declNames = extractDeclNames(that.getDecls());
        Set<Id> vars = declNames.first();
        Set<IdOrOpOrAnonymousName> gettersAndSetters = declNames.second();
        Set<IdOrOpOrAnonymousName> fns = declNames.third();

        Option<Type> typeResult = recurOnOptionOfType(that.getExprType());

        Pair<Set<Id>, Set<IdOrOpOrAnonymousName>> inherited = inheritedMethods(extendsClause);
        Set<Id> inheritedGettersAndSetters = inherited.first();
        Set<IdOrOpOrAnonymousName> inheritedMethods = inherited.second();

        ExprDisambiguator v = this.
            extendWithVars(vars).
            extendWithFns(inheritedMethods).
            extendWithSelf(that.getSpan()).
            extendWithFns(fns).
            // TODO The following two extensions are problematic; getters and setters should
            // not be referred to without explicit receivers in most (all?) cases. But the
            // libraries break horribly if we leave them off.
            extendWithFns(inheritedGettersAndSetters, vars).
            extendWithFns(gettersAndSetters, vars);

        return forObjectExprOnly(that, typeResult,
                                 extendsClause,
                                 v.recurOnListOfDecl(that.getDecls()));
    }

    // Make sure we don't infinitely explore supertraits that are acyclic
    public static class HierarchyHistory {
        final private Set<Type> explored;

        public HierarchyHistory() {	explored = Collections.emptySet();	}
        private HierarchyHistory(Set<Type> explored) { this.explored = explored; }

        public HierarchyHistory explore(Type t) {
            return new HierarchyHistory(CollectUtil.union(explored, t));
        }

        public boolean hasExplored(Type t) {
            return explored.contains(t);
        }
    }

    /**
     * Given a list of TraitTypeWhere that some trait or object extends,
     * this method returns a pair of lists of getters/setter ids and method ids that
     * the trait receives through inheritance. The implementation of this method is somewhat
     * involved, since at this stage of compilation, not all types are
     * fully formed. (In particular, types in extends clauses of types that
     * are found in the GlobalEnvironment.)
     * @param extended_traits
     * @return
     */
    private Pair<Set<Id>, Set<IdOrOpOrAnonymousName>> inheritedMethods(List<TraitTypeWhere> extended_traits) {
        return inheritedMethodsHelper(new HierarchyHistory(), extended_traits);
    }

    private Pair<Set<Id>, Set<IdOrOpOrAnonymousName>> inheritedMethodsHelper(HierarchyHistory h,
                                                              List<TraitTypeWhere> extended_traits) {
        Set<Id> gettersAndSetters = new HashSet<Id>();
        Set<IdOrOpOrAnonymousName> methods = new HashSet<IdOrOpOrAnonymousName>();
        for( TraitTypeWhere trait_ : extended_traits ) {

            BaseType type_ = trait_.getBaseType();

            if( h.hasExplored(type_) )
                continue;
            h = h.explore(type_);

            // Trait types or VarTypes can represent traits at this phase of compilation.
            Id trait_name;
            if( type_ instanceof TraitType ) {
                trait_name = ((TraitType)type_).getName();
            }
            else if( type_ instanceof VarType ) {
                trait_name = ((VarType)type_).getName();
            }
            else {
                // Probably ANY
                return new Pair<Set<Id>, Set<IdOrOpOrAnonymousName>>(Collections.<Id>emptySet(),
                                                                     Collections.<IdOrOpOrAnonymousName>emptySet());
             }

            TypeConsIndex tci = this._env.typeConsIndex(trait_name);
            if( tci instanceof TraitIndex ) {
                TraitIndex ti = (TraitIndex)tci;
                // Add dotted methods
                methods.addAll(ti.dottedMethods().firstSet());
                // Add getters
                gettersAndSetters.addAll(ti.getters().keySet());
                // Add setters
                gettersAndSetters.addAll(ti.setters().keySet());
                // For now we won't add functional methods. They are not received through inheritance.

                // Now recursively add methods from trait's extends clause
                Pair<Set<Id>, Set<IdOrOpOrAnonymousName>> inherited = inheritedMethodsHelper(h, ti.extendsTypes());
                gettersAndSetters.addAll(inherited.first());
                methods.addAll(inherited.second());
            }
            else {
                // Probably ANY
                return new Pair<Set<Id>, Set<IdOrOpOrAnonymousName>>(Collections.<Id>emptySet(),
                                                                     Collections.<IdOrOpOrAnonymousName>emptySet());
            }


        }
        return new Pair<Set<Id>, Set<IdOrOpOrAnonymousName>>(gettersAndSetters, methods);
    }

    @Override public Node forComponent(final Component that) {
        inComponent = true;
        return super.forComponent( that );
    }

    /**
     * When recurring on a TraitDecl, we first need to extend the
     * environment with all the newly bound static parameters that
     * can be used in an expression context.
     * TODO: Handle variables bound in where clauses.
     * TODO: Insert inherited method names into the environment.
     */
    @Override public Node forTraitDecl(final TraitDecl that) {
        ExprDisambiguator v = this.extendWithVars(extractStaticExprVars(NodeUtil.getStaticParams(that)));
        List<TraitTypeWhere> extendsClause = v.recurOnListOfTraitTypeWhere(NodeUtil.getExtendsClause(that));

        // Include trait declarations and inherited methods
        Triple<Set<Id>, Set<IdOrOpOrAnonymousName>, Set<IdOrOpOrAnonymousName>> declNames =
            extractDeclNames(NodeUtil.getDecls(that));
        Set<Id> vars = declNames.first();
        Set<IdOrOpOrAnonymousName> gettersAndSetters = declNames.second();
        Set<IdOrOpOrAnonymousName> fns = declNames.third();

        Pair<Set<Id>, Set<IdOrOpOrAnonymousName>> inherited = inheritedMethods(extendsClause);
        Set<Id> inheritedGettersAndSetters = inherited.first();
        Set<IdOrOpOrAnonymousName> inheritedMethods = inherited.second();

        // Do not extend the environment with "fields", getters, or setters in a trait.
        // References to all three must have an explicit receiver.
        v = this.
            extendWithVars(extractStaticExprVars(NodeUtil.getStaticParams(that))).
            extendWithFns(inheritedGettersAndSetters).
            extendWithFns(inheritedMethods).
            extendWithSelf(that.getSpan()).
            extendWithVars(vars).
            extendWithFns(fns).
            // TODO The following two extensions are problematic; getters and setters should
            // not be referred to without explicit receivers in most (all?) cases. But the
            // libraries break horribly if we leave them off.
            extendWithFns(inheritedGettersAndSetters).
            extendWithFns(gettersAndSetters);

        if ( ! inComponent )
            v.checkForShadowingVars(vars);

        TraitTypeHeader header = (TraitTypeHeader)forTraitTypeHeaderOnly(that.getHeader(),
				(Id) NodeUtil.getName(that).accept(v),
				v.recurOnListOfStaticParam(NodeUtil.getStaticParams(that)),
                                v.recurOnOptionOfWhereClause(NodeUtil.getWhereClause(that)),
                                Option.<List<BaseType>>none(),
                                Option.<Contract>none(),
				extendsClause,
				v.recurOnListOfDecl(NodeUtil.getDecls(that)));

        return forTraitDeclOnly(that, header,
				v.recurOnListOfBaseType(NodeUtil.getExcludesClause(that)),
				v.recurOnOptionOfListOfBaseType(NodeUtil.getComprisesClause(that)));
    }

    /**
     * When recurring on an ObjectDecl, we first need to extend the
     * environment with all the newly bound static parameters that can
     * be used in an expression context, along with all the object parameters.
     * TODO: Handle variables bound in where clauses.
     * TODO: Insert inherited method names into the environment.
     */
    @Override public Node forObjectDecl(final ObjectDecl that) {
        ExprDisambiguator v = this.extendWithVars(extractStaticExprVars(NodeUtil.getStaticParams(that)));
        List<TraitTypeWhere> extendsClause = v.recurOnListOfTraitTypeWhere(NodeUtil.getExtendsClause(that));

        // Include trait declarations and inherited methods
        Triple<Set<Id>,Set<IdOrOpOrAnonymousName>, Set<IdOrOpOrAnonymousName>> declNames = extractDeclNames(NodeUtil.getDecls(that));
        Set<Id> vars = declNames.first();
        Set<IdOrOpOrAnonymousName> gettersAndSetters = declNames.second();
        // fns does not contain getters and setters
        Set<IdOrOpOrAnonymousName> fns = declNames.third();

        Set<Id> params = extractParamNames(NodeUtil.getParams(that));
        Set<Id> fields = CollectUtil.union(params, vars);

        Pair<Set<Id>, Set<IdOrOpOrAnonymousName>> inherited = inheritedMethods(extendsClause);
        Set<Id> inheritedGettersAndSetters = inherited.first();
        Set<IdOrOpOrAnonymousName> inheritedMethods = inherited.second();

        v = this.extendWithVars(extractStaticExprVars
				(NodeUtil.getStaticParams(that))).
            extendWithSelf(that.getSpan()).
            extendWithVars(extractParamNames(NodeUtil.getParams(that))).
            extendWithVars(vars).extendWithFns(fns).
            extendWithFns(inheritedMethods).
            // TODO The following two extensions are problematic; getters and setters should
            // not be referred to without explicit receivers in most (all?) cases. But the
            // libraries break horribly if we leave them off.
            extendWithFns(inheritedGettersAndSetters, fields).
            extendWithFns(gettersAndSetters, fields);

        TraitTypeHeader header = (TraitTypeHeader)forTraitTypeHeaderOnly(that.getHeader(),
                                 (Id) NodeUtil.getName(that).accept(v),
                                 v.recurOnListOfStaticParam(NodeUtil.getStaticParams(that)),
                                 v.recurOnOptionOfWhereClause(NodeUtil.getWhereClause(that)),
                                 v.recurOnOptionOfListOfBaseType(NodeUtil.getThrowsClause(that)),
                                 v.recurOnOptionOfContract(NodeUtil.getContract(that)),
                                 extendsClause,
                                 v.recurOnListOfDecl(NodeUtil.getDecls(that)));

        return forObjectDeclOnly(that, header,
                                 v.recurOnOptionOfListOfParam(NodeUtil.getParams(that)));
    }

    /**
     * When recurring on a FnDecl, we first need to extend the
     * environment with all the newly bound static parameters that
     * can be used in an expression context, along with all function
     * parameters and 'self'.
     * TODO: Handle variables bound in where clauses.
     */
    @Override public Node forFnDecl(FnDecl that) {
        Set<Id> staticExprVars = extractStaticExprVars(NodeUtil.getStaticParams(that));
        Set<Id> params = extractParamNames(NodeUtil.getParams(that));
        checkForValidParams(params);
        ExprDisambiguator v = extendWithVars(staticExprVars).extendWithVars(params);

        // No need to recur on the name, as we will not modify it and we have already checked
        // for shadowing in forObjectDecl. Also, if this FnDecl is a getter, we allow it
        // to share its name with a field, so blindly checking for shadowing at this point
        // doesn't work.
        return forFnDeclOnly(that,
                            (IdOrOpOrAnonymousName) NodeUtil.getName(that),
                            v.recurOnListOfStaticParam(NodeUtil.getStaticParams(that)),
                            v.recurOnListOfParam(NodeUtil.getParams(that)),
                            v.recurOnOptionOfType(NodeUtil.getReturnType(that)),
                            v.recurOnOptionOfListOfBaseType(NodeUtil.getThrowsClause(that)),
                            v.recurOnOptionOfWhereClause(NodeUtil.getWhereClause(that)),
                            v.recurOnOptionOfContract(NodeUtil.getContract(that)),
                            that.getUnambiguousName(),
                            v.recurOnOptionOfExpr(NodeUtil.getBody(that)),
                            that.getImplementsUnambiguousName());
    }



    /**
     * Currently we don't descend into dimensions or units.
     */
    @Override
	public Node forTaggedDimType(TaggedDimType that) {
        return forTaggedDimTypeOnly(that,
                                    (Type)that.getElemType().accept(this),
                                    that.getDimExpr(),
                                    that.getUnitExpr());
    }

    /**
     * Currently we don't descend into dimensions or units.
     */
    @Override
	public Node forTaggedUnitType(TaggedUnitType that) {
        return forTaggedUnitTypeOnly(that,
                                     (Type)that.getElemType().accept(this),
                                     that.getUnitExpr());
    }


    /**
     * Currently we don't do any disambiguation of dimension or unit declarations.
     */
    @Override public Node forDimDecl(DimDecl that) { return that; }
    @Override public Node forUnitDecl(UnitDecl that) { return that; }

    /**
     * When recurring on a FnExpr, we first need to extend the
     * environment with any newly bound static parameters that
     * can be used in an expression context, along with all function
     * parameters and 'self'.
     */
    @Override public Node forFnExpr(FnExpr that) {
        Set<Id> staticExprVars = extractStaticExprVars(NodeUtil.getStaticParams(that));
        Set<Id> params = extractParamNames(NodeUtil.getParams(that));
        ExprDisambiguator v = extendWithVars(staticExprVars).extendWithVars(params);

        Option<Type> type_result = recurOnOptionOfType(that.getExprType());
        return forFnExprOnly(that, type_result,
                             (IdOrOpOrAnonymousName) NodeUtil.getName(that).accept(v),
                             v.recurOnListOfStaticParam(NodeUtil.getStaticParams(that)),
                             v.recurOnListOfParam(NodeUtil.getParams(that)),
                             v.recurOnOptionOfType(NodeUtil.getReturnType(that)),
                             v.recurOnOptionOfWhereClause(NodeUtil.getWhereClause(that)),
                             v.recurOnOptionOfListOfBaseType(NodeUtil.getThrowsClause(that)),
                             (Expr) that.getBody().accept(v));
    }




    @Override
	public Node forCatch(Catch that) {
        ExprDisambiguator v = this.extendWithVars(Collections.singleton(that.getName()));
        return forCatchOnly(that,
                            (Id)that.getName().accept(v),
                            v.recurOnListOfCatchClause(that.getClauses()));
    }

    /**
     * Contracts are implicitly allowed to refer to a variable, "outcome."
     */
    @Override
	public Node forContract(Contract that) {
        ExprDisambiguator v = extendWithOutcome(that.getSpan());
        return forContractOnly(that,
                               v.recurOnOptionOfListOfExpr(that.getRequiresClause()),
                               v.recurOnOptionOfListOfEnsuresClause(that.getEnsuresClause()),
                               v.recurOnOptionOfListOfExpr(that.getInvariantsClause()));
    }

    @Override
	public Node forAccumulator(Accumulator that) {
        // Accumulator can bind variables
        Pair<List<GeneratorClause>, Set<Id>> pair = bindInListGenClauses(this, that.getGens());
        ExprDisambiguator extended_d = this.extendWithVars(pair.second());
        Option<Type> type_result = recurOnOptionOfType(that.getExprType());
        return forAccumulatorOnly(that, type_result,
                                  recurOnListOfStaticArg(that.getStaticArgs()),
                                  (Op)that.getAccOp().accept(this),
                                  pair.first(),
                                  (Expr)that.getBody().accept(extended_d));
    }

    /**
     * An if clause has a generator that can potentially create a new binding.
     * Here we must extend the context.
     */
    @Override
	public Node forIfClause(IfClause that) {
        GeneratorClause gen = that.getTestClause();
        ExprDisambiguator e_d = this.extendWithVars(Useful.set(gen.getBind()));

        return forIfClauseOnly(that,
                               (GeneratorClause)that.getTestClause().accept(this),
                               (Block)that.getBody().accept(e_d));
    }

    /**
     * While loops have generator clauses that can bind variables in the body.
     */
    @Override
	public Node forWhile(While that) {
        GeneratorClause gen = that.getTestExpr();
        ExprDisambiguator e_d = this.extendWithVars(Useful.set(gen.getBind()));

        Option<Type> type_result = recurOnOptionOfType(that.getExprType());
        return forWhileOnly(that, type_result,
                            (GeneratorClause)that.getTestExpr().accept(this),
                            (Do)that.getBody().accept(e_d));
    }



    /**
     * Typecase can bind new variables in the clauses.
     */
    @Override
	public Node forTypecase(Typecase that) {
        Set<Id> bound_ids = Useful.set(that.getBindIds());
        ExprDisambiguator e_d;
        if ( that.getBindExpr().isSome() )
            e_d = this.extendWithVars(bound_ids);
        else
            e_d = this.extendWithVarsNoCheck(bound_ids);

        Option<Type> type_result = recurOnOptionOfType(that.getExprType());
        return forTypecaseOnly(that, type_result,
                               that.getBindIds(),
                               that.getBindExpr().isSome() ? Option.<Expr>some((Expr)that.getBindExpr().unwrap().accept(this)) : Option.<Expr>none(),
                               e_d.recurOnListOfTypecaseClause(that.getClauses()),
                               that.getElseClause().isSome() ? Option.<Block>some((Block)that.getElseClause().unwrap().accept(e_d)) : Option.<Block>none());
    }


    private static Pair<List<GeneratorClause>, Set<Id>> bindInListGenClauses(final ExprDisambiguator cur_disam,
                                                                             List<GeneratorClause> gens) {

        return IterUtil.fold(gens, Pair.<List<GeneratorClause>,Set<Id>>make(new LinkedList<GeneratorClause>(),new HashSet<Id>()),
                             new Lambda2<Pair<List<GeneratorClause>,Set<Id>>,GeneratorClause,Pair<List<GeneratorClause>,Set<Id>>>(){
                                 public Pair<List<GeneratorClause>, Set<Id>> value(
                                                                                   Pair<List<GeneratorClause>, Set<Id>> arg0,
                                                                                   GeneratorClause arg1) {
                                     // given the bindings thus far, rebuild the current GeneratorClause with the new bindings
                                     // pass along that generator's bindings.
                                     Set<Id> previous_bindings = arg0.second();
                                     ExprDisambiguator extended_d = cur_disam.extendWithVars(previous_bindings);
                                     GeneratorClause new_gen = (GeneratorClause)arg1.accept(extended_d);
                                     Set<Id> new_bindings = Useful.union(previous_bindings,Useful.set(arg1.getBind()));

                                     arg0.first().add(new_gen);
                                     return Pair.make(arg0.first(), new_bindings);
                                 }});
    }


    /**
     * for loops have generator clauses that bind in later generator clauses.
     */
    @Override
	public Node forFor(For that) {
        Pair<List<GeneratorClause>, Set<Id>> pair = bindInListGenClauses(this, that.getGens());
        Option<Type> type_result = recurOnOptionOfType(that.getExprType());

        ExprDisambiguator new_disambiguator = this.extendWithVars(pair.second());
        return forForOnly(that, type_result,
                          pair.first(),
                          (Block)that.getBody().accept(new_disambiguator));
    }

    /** LetFns introduce local functions in scope within the body. */
    @Override public Node forLetFn(LetFn that) {
        Set<IdOrOpOrAnonymousName> definedNames = extractDefinedFnNames(that.getFns());
        ExprDisambiguator v = extendWithLocalFns(definedNames);
        List<FnDecl> fnsResult = v.recurOnListOfFnDecl(that.getFns());
        List<Expr> bodyResult = v.recurOnListOfExpr(that.getBody());
        Option<Type> type_result = recurOnOptionOfType(that.getExprType());
        return forLetFnOnly(that, type_result, bodyResult, fnsResult);
    }


    private Set<IdOrOpOrAnonymousName> extractDefinedFnNames(Iterable<FnDecl> fnDefs) {
        Set<IdOrOpOrAnonymousName> result = new HashSet<IdOrOpOrAnonymousName>();
        for (FnDecl fd : fnDefs) { result.add(fd.getName()); }
        // multiple instances of the same name are allowed
        return result;
    }

    /** VarRefs can be made qualified or translated into FnRefs. */
    @Override public Node forVarRef(VarRef that) {
        Id name = that.getVarId();

        // singleton object
        if ( NodeUtil.isSingletonObject(that) ) {
            Set<Id> objs = _env.explicitTypeConsNames(name);
            if( objs.isEmpty() ) {
                objs = _env.onDemandTypeConsNames(name);
            }

            if( objs.isEmpty() ) {
                return that;
            }
            else if( objs.size() == 1 ) {
                return ExprFactory.makeVarRef(that.getSpan(), IterUtil.first(objs), that.getStaticArgs());
            }
            else {
                error("Name may refer to: " + NodeUtil.namesString(objs), that);
                return that;
            }
        }

        Option<APIName> api = name.getApiName();
        ConsList<Id> fields = ConsList.empty();
        Expr result = null;

        // First, try to interpret it as a qualified name
        while (result == null && api.isSome()) {
            APIName givenApiName = api.unwrap();
            Option<APIName> realApiNameOpt = _env.apiName(givenApiName);

            if (realApiNameOpt.isSome()) {
                APIName realApiName = realApiNameOpt.unwrap();
                Id newId = NodeFactory.makeId(realApiName, name);
                if (_env.hasQualifiedVariable(newId)) {
                    if (fields.isEmpty() && givenApiName == realApiName) {
                        // no change -- no need to recreate the VarRef
                        return that;
                    }
                    else { result = ExprFactory.makeVarRef(newId); }
                }
                else if (_env.hasQualifiedFunction(newId)) {
                    result = ExprFactory.makeFnRef(newId, name);

                    // TODO: insert correct number of to-infer arguments?
                }
                else {
                    error("Unrecognized name: " + NodeUtil.nameString(name), that);
                    return that;
                }
            }

            else {
                // shift all names to the right, and try a smaller api name
                List<Id> ids = givenApiName.getIds();
                fields = ConsList.cons(name, fields);
                name = IterUtil.last(ids);
                Iterable<Id> prefix = IterUtil.skipLast(ids);
                if (IterUtil.isEmpty(prefix)) { api = Option.none(); }
                else { api = Option.some(NodeFactory.makeAPIName(prefix)); }
            }
        }

        // Second, try to interpret it as an unqualified name.
        if (result == null) {
            // api.isNone() must be true
            Set<Id> vars = _env.explicitVariableNames(name);
            Set<IdOrOp> fns = _env.explicitFunctionNames(name);
            Set<Id> objs = _env.explicitTypeConsNames(name);

            if ( vars.size() == 1 && fns.isEmpty() && objs.isEmpty() ) {
                Id newName = IterUtil.first(vars);

                if (newName.getApiName().isNone() && newName == name && fields.isEmpty()) {
                    // no change -- no need to recreate the VarRef
                    return that;
                }
                else { result = ExprFactory.makeVarRef(that.getSpan(), newName); }
            }
            else if (vars.isEmpty() && !fns.isEmpty() ) {
                result = ExprFactory.makeFnRef(name,CollectUtil.makeList(fns));
                // TODO: insert correct number of to-infer arguments?
            }
            else if( vars.size() == 1&& fns.isEmpty() && objs.size() == 1 ) {
                result = ExprFactory.make_RewriteObjectRef(that.isParenthesized(), IterUtil.first(objs));
            }
            else if (!vars.isEmpty() || !fns.isEmpty() || !objs.isEmpty()) {
                // To be replaced by a 'shadowing' pass
                //Set<Id> varsFnsAndObjs = CollectUtil.union(CollectUtil.union(vars, fns), objs);
                //error("Name may refer to: " + NodeUtil.namesString(varsFnsAndObjs), name);
                return that;
            }
            else {
                // Turn off error message on this branch until we can ensure
                // that the VarRef doesn't resolve to an inherited method.
                // For now, assume it does refer to an inherited method.
                if (fields.isEmpty()) {
                    // no change -- no need to recreate the VarRef
                    error("No definitions match variable reference " + name +".", name);
                    return that;
                }
                else {
                    result = ExprFactory.makeVarRef(name.getSpan(), name);
                }
                // error("Unrecognized name: " + NodeUtil.nameString(name), that);
                // return that;
            }
        }

        // result is now non-null
        for (Id field : fields) {
            result = ExprFactory.makeFieldRef(result, field);
        }
        if (that.isParenthesized()) {
            result = ExprFactory.makeInParentheses(result);
        }
        return result;
    }

    @Override public Node forOpRef(OpRef that) {
        Option<FunctionalRef> result_ = opRefHelper((FunctionalRef)that);

        if ( result_.isNone() ) {
            // Make sure to populate the 'originalName' field.
            return ExprFactory.makeOpRef(that.getSpan(), that.isParenthesized(),
                                         that.getExprType(), that.getStaticArgs(),
                                         that.getLexicalDepth(),
                                         (Op)IterUtil.first(that.getNames()),
                                         that.getNames(),
                                         that.getOverloadings(),
                                         that.getOverloadingType());
        }
        else {
            return result_.unwrap();
        }
    }

    @Override public Node forFnRef(FnRef that) {
        // Many FnRefs will be covered by the VarRef case, since many functions are parsed
        // as variables. FnRefs can be parsed if, for example, explicit static arguments are
        // provided. These function references must still be disambiguated.

        IdOrOp fn_name = IterUtil.first(that.getNames());
        Set<IdOrOp> fns = _env.explicitFunctionNames(fn_name);

        if( fns.isEmpty() ) {
            if ( fn_name instanceof Id ) {
                // Could be a singleton object with static arguments.
                Set<Id> types = _env.explicitTypeConsNames((Id)fn_name);
                if( !types.isEmpty() ) {
                    // create _RewriteObjectRef
                    VarRef obj = ExprFactory.makeVarRef(that.getSpan(), (Id)fn_name, that.getStaticArgs());
                    return obj.accept(this);
                }
            } else {
                //error("Function " + that + " could not be disambiguated.", that);
                // TODO: The above line is giving fits to the tests, but it'd be nice to pass.
                return ExprFactory.makeFnRef(that.getSpan(), that.isParenthesized(), (Id)fn_name,
                                             that.getNames(), that.getStaticArgs());
            }
        }

        return ExprFactory.makeFnRef(that.getSpan(), that.isParenthesized(), (Id)fn_name,
                                     CollectUtil.makeList(fns), that.getStaticArgs());
    }

    /**
     * Disambiguates an OpRef, but instead of reporting an error if it cannot be
     * disambiguated, it returns NONE, which other methods can then used to decide
     * if they want to report an error.
     */
    private Option<FunctionalRef> opRefHelper(FunctionalRef that) {
        Op op_name = (Op)IterUtil.first(that.getNames());
        Set<? extends IdOrOp> ops = _env.explicitFunctionNames(op_name);
        if (ops.isEmpty()) {
            ops = _env.onDemandFunctionNames(op_name);
        }

        if (ops.isEmpty()) {
            return Option.none();
        }

        FunctionalRef result = ExprFactory.makeOpRef(that.getSpan(), that.isParenthesized(),
                                                     that.getExprType(),
                                                     that.getStaticArgs(),
                                                     that.getLexicalDepth(),
                                                     op_name, CollectUtil.makeList(ops),
                                                     that.getOverloadings(),
                                                     that.getOverloadingType());
        return Option.<FunctionalRef>some(result);
    }


    @Override
	public Node forOpExpr(OpExpr that) {
        // OpExpr checks to make sure its OpRef can be disambiguated, since
        // forOpRef will not automatically report an error.
        FunctionalRef op_result;
        Option<FunctionalRef> _op_result = opRefHelper(that.getOp());
        if( _op_result.isSome() ) {
            op_result = (FunctionalRef)_op_result.unwrap();
        }
        else {
            String op_name = IterUtil.first(that.getOp().getNames()).stringName();
            error("Operator " + op_name + " cannot be disambiguated.", that.getOp());
            op_result = (FunctionalRef)recur(that.getOp());
        }
        Option<Type> type_result = recurOnOptionOfType(that.getExprType());
        List<Expr> args_result = recurOnListOfExpr(that.getArgs());
        return forOpExprOnly(that, type_result, op_result, args_result);
    }

    @Override public Node forLabel(Label that) {
        Set<Id> labels = Useful.set(that.getName());
        checkForShadowingVars(labels);
        NameEnv newEnv = new LocalVarEnv(_env, labels);
        ExprDisambiguator v =  new ExprDisambiguator(newEnv, _uninitializedNames, _errors,
                                                     Option.wrap(that.getName()));
        Option<Type> type_result = recurOnOptionOfType(that.getExprType());
        return forLabelOnly(that, type_result, (Id) that.getName().accept(v),
                            (Block) that.getBody().accept(v));
    }

    @Override public Node forExitOnly(Exit that, Option<Type> exprType_result, Option<Id> target_result, Option<Expr> returnExpr_result) {
        Option<Id> target = target_result.isSome() ? target_result : _innerMostLabel;
        Option<Expr> with = returnExpr_result.isSome() ?
            returnExpr_result :
            wrap((Expr)ExprFactory.makeVoidLiteralExpr(that.getSpan()));

        if (target.isNone() || _innerMostLabel.isNone()) {
            error("Exit occurs outside of a label", that);
        }
        Exit newExit = ExprFactory.makeExit(that.getSpan(), that.isParenthesized(),
                                            exprType_result, target, with);
        if (newExit.equals(that)) {
            return that;
        } else {
            return newExit;
        }
    }


    @Override
	public Node forGrammarDecl(GrammarDecl that) {
        return that;
    }
}
