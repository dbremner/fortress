/*******************************************************************************
    Copyright 2010 Sun Microsystems, Inc.,
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

package com.sun.fortress.compiler;

import static com.sun.fortress.exceptions.InterpreterBug.bug;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.sun.fortress.Shell;
import com.sun.fortress.compiler.index.ApiIndex;
import com.sun.fortress.compiler.index.CompilationUnitIndex;
import com.sun.fortress.compiler.index.ComponentIndex;
import com.sun.fortress.compiler.typechecker.InferenceVarInserter;
import com.sun.fortress.compiler.typechecker.InferenceVarReplacer;
import com.sun.fortress.compiler.typechecker.TypeChecker;
import com.sun.fortress.compiler.typechecker.TypeCheckerOutput;
import com.sun.fortress.compiler.typechecker.TypeCheckerResult;
import com.sun.fortress.compiler.typechecker.TypeEnv;
import com.sun.fortress.compiler.typechecker.TypeNormalizer;
import com.sun.fortress.compiler.typechecker.TypesUtil;
import com.sun.fortress.exceptions.StaticError;
import com.sun.fortress.nodes.Api;
import com.sun.fortress.nodes.APIName;
import com.sun.fortress.nodes.CompilationUnit;
import com.sun.fortress.nodes.Component;
import com.sun.fortress.nodes.Node;
import com.sun.fortress.nodes.Type;
import com.sun.fortress.nodes_util.NodeUtil;
import com.sun.fortress.nodes_util.Nodes;
import com.sun.fortress.repository.FortressRepository;
import com.sun.fortress.scala_src.linker.ApiLinker;
import com.sun.fortress.scala_src.linker.CompoundApiChecker;
import com.sun.fortress.scala_src.typechecker.ApiTypeExtractor;
import com.sun.fortress.scala_src.typechecker.CoercionTest;
import com.sun.fortress.scala_src.typechecker.ExclusionOracle;
import com.sun.fortress.scala_src.typechecker.ExportChecker;
import com.sun.fortress.scala_src.typechecker.IndexBuilder;
import com.sun.fortress.scala_src.typechecker.TraitTable;
import com.sun.fortress.scala_src.typechecker.Thunker;
import com.sun.fortress.scala_src.typechecker.CyclicReferenceChecker;
import com.sun.fortress.scala_src.typechecker.TypeHierarchyChecker;
import com.sun.fortress.scala_src.typechecker.TypeWellFormedChecker;
import com.sun.fortress.scala_src.typechecker.OverloadingChecker;
import com.sun.fortress.scala_src.typechecker.STypeChecker;
import com.sun.fortress.scala_src.typechecker.TryChecker;
import com.sun.fortress.scala_src.typechecker.STypeCheckerFactory;
import com.sun.fortress.scala_src.typechecker.staticenv.STypeEnv;
import com.sun.fortress.scala_src.types.TypeAnalyzer;
import com.sun.fortress.scala_src.useful.ErrorLog;
import com.sun.fortress.scala_src.useful.Lists;
import com.sun.fortress.useful.Debug;
import edu.rice.cs.plt.iter.IterUtil;
import edu.rice.cs.plt.tuple.Option;
import edu.rice.cs.plt.tuple.Pair;

/**
 * Verifies all static properties of a valid Fortress program that require
 * interpreting types.  Assumes all names referring to APIs are fully-qualified,
 * and that the other transformations handled by the {@link Disambiguator} have
 * been performed.  In addition to checking the program, performs the following
 * transformations:
 * <ul>
 * <li>All unknown placeholder types are provided explicit (inferred) values.</li>
 * <li>Explicit coercions are added where needed. (Not yet!) </li>
 * <li>Juxtapositions are given a binary structure.</li>
 * <li>FieldRefs that refer to methods and that are followed by an argument expression
 *     become MethodInvocations.</li>
 * </li>
 */
public class StaticChecker {

    public static class ApiResult extends StaticPhaseResult {
        private final Map<APIName, ApiIndex> _apis;
        private final List<APIName> _failedApis;
        private final TypeCheckerOutput _typeCheckerOutput;

        public ApiResult(Map<APIName, ApiIndex> apis,
                         List<APIName> failedApis,
                         Iterable<? extends StaticError> errors,
                         TypeCheckerOutput typeCheckerOutput) {
            super(errors);
            _apis = apis;
            _failedApis = failedApis;
            _typeCheckerOutput = typeCheckerOutput;
        }
        public Map<APIName, ApiIndex> apis() { return _apis; }
        public List<APIName> failed() { return _failedApis; }

        public TypeCheckerOutput typeCheckerOutput() {
            return this._typeCheckerOutput;
        }
    }

    public static class ComponentResult extends StaticPhaseResult {
        private final Map<APIName, ComponentIndex> _components;
        private final List<APIName> _failedComponents;
        private final TypeCheckerOutput _typeCheckerOutput;

        public ComponentResult(Map<APIName, ComponentIndex> components,
                               List<APIName> failedComponents,
                               Iterable<? extends StaticError> errors,
                               TypeCheckerOutput typeCheckerOutput) {
            super(errors);
            _components = components;
            _failedComponents = failedComponents;
            _typeCheckerOutput = typeCheckerOutput;
        }
        public Map<APIName, ComponentIndex> components() { return _components; }
        public List<APIName> failed() { return _failedComponents; }

        public TypeCheckerOutput typeCheckerOutput() {
            return this._typeCheckerOutput;
        }
    }

    /**
     * Check the given apis. To support circular references, the apis should appear
     * in the given environment.
     */
    public static ApiResult checkApis(Map<APIName, ApiIndex> apis,
                                      GlobalEnvironment env) {
        HashSet<Api> checkedApis = new HashSet<Api>();
        List<APIName> failedApis = new ArrayList<APIName>();
        Iterable<? extends StaticError> errors = new HashSet<StaticError>();
        TypeCheckerOutput type_checker_output = TypeCheckerOutput.emptyOutput();

        for (APIName apiName : apis.keySet()) {
            TypeCheckerResult checked = checkCompilationUnit(apis.get(apiName), env, true);
            checkedApis.add((Api)checked.ast());
            if (!checked.isSuccessful()) failedApis.add(apiName);
            errors = IterUtil.compose(checked.errors(), errors);
            type_checker_output = new TypeCheckerOutput( type_checker_output,
                                                         checked.getTypeCheckerOutput() );
        }
        return new ApiResult
            (IndexBuilder.buildApis(checkedApis,
                                    env,
                                    System.currentTimeMillis()).apis(),
             failedApis,
             errors,
             type_checker_output);
    }

    /** Statically check the given components. */
    public static ComponentResult
        checkComponents(Map<APIName, ComponentIndex> components,
                        GlobalEnvironment env) {
        HashSet<Component> checkedComponents = new HashSet<Component>();
        List<APIName> failedComponents = new ArrayList<APIName>();
        Iterable<? extends StaticError> errors = new HashSet<StaticError>();
        TypeCheckerOutput type_checker_output = TypeCheckerOutput.emptyOutput();

        for (APIName componentName : components.keySet()) {
            TypeCheckerResult checked = checkCompilationUnit(components.get(componentName),
                                                             env, false);
            checkedComponents.add((Component)checked.ast());
            if (!checked.isSuccessful()) failedComponents.add(componentName);
            errors = IterUtil.compose(checked.errors(), errors);
            type_checker_output = new TypeCheckerOutput( type_checker_output,
                                                         checked.getTypeCheckerOutput() );
        }
        return new ComponentResult
            (IndexBuilder.buildComponents(checkedComponents,
                                          System.currentTimeMillis()).components(),
             failedComponents,
             errors,
             type_checker_output);
    }

    public static TypeCheckerResult checkCompilationUnit(CompilationUnitIndex index,
                                                         GlobalEnvironment env,
                                                         boolean isApi) {
        if (Shell.getTypeChecking() == true) {
            CompilationUnit ast = index.ast();
            List<StaticError> errors = new ArrayList<StaticError>();

            if (isApi) {
                Api api_ast = (Api)ast;
//                 Nodes.printNode((Api)api_ast, "api_ast.");

                // Check if this is a compound API, and, if so, link it into a single API.
                // The AST associated with an ApiIndex is always an Api.
                errors = new CompoundApiChecker(env.apis(), env).check(api_ast);
                if (! errors.isEmpty()) {
                    return new TypeCheckerResult(ast, errors);
                }
                ast = new ApiLinker(env.apis(), env).link(api_ast);
//                 Nodes.printNode((Api)ast, "api_linked_ast.");
                index = IndexBuilder.buildCompilationUnitIndex(api_ast,
                                                               System.currentTimeMillis(),
                                                               isApi);
            }

            // Check type hierarchy to ensure acyclicity.
            TypeHierarchyChecker typeHierarchyChecker =
                new TypeHierarchyChecker(index, env, isApi);
            errors.addAll(typeHierarchyChecker.checkHierarchy());
            if (! errors.isEmpty()) {
                return new TypeCheckerResult(ast, errors);
            }

            if (!isApi) { // if component
                // Replace implicit types with explicit ones.
                if (!Shell.getScala()) {
                    ast = (Component)ast.accept(new InferenceVarInserter());
                } else {
                    ApiTypeExtractor typeExtractor =
                        new ApiTypeExtractor((ComponentIndex)index, env);
                    ast = (Component)typeExtractor.check();
                    errors.addAll(typeExtractor.getErrors());
                    if(!errors.isEmpty())
                        return new TypeCheckerResult(ast,errors);
                }
            }

            ast = (CompilationUnit)ast.accept(new TypeNormalizer());
            index = buildIndex(ast, isApi);

            TraitTable traitTable = new TraitTable(index, env);
            TypeAnalyzer typeAnalyzer = com.sun.fortress.scala_src.types.TypeAnalyzer$.MODULE$.make(traitTable);

            if (Shell.testCoercion())
                errors.addAll(new CoercionTest(typeAnalyzer).run());

            errors.addAll(typeHierarchyChecker.checkAcyclicHierarchy(typeAnalyzer));
            errors.addAll(new TypeWellFormedChecker(index, env, typeAnalyzer).check());

            TypeCheckerResult result;
            if (isApi) {
                result = new TypeCheckerResult(ast, errors);
            } else {
                ComponentIndex componentIndex = (ComponentIndex)index;
                Component component_ast = (Component)ast;
                if ( ! Shell.getScala() ) {
                    throw new Error("The Java version of the type checker is gone now.");
                } else {
                    STypeEnv typeEnv = com.sun.fortress.scala_src.typechecker.staticenv.STypeEnv$.MODULE$.make(componentIndex);
                    ErrorLog log = new ErrorLog();

                    //Create thunks for when the user elides function return types
                    CyclicReferenceChecker cycleChecker = new CyclicReferenceChecker(log);
                    STypeChecker thunkChecker =
                      STypeCheckerFactory.make(componentIndex, traitTable, typeEnv, typeAnalyzer, cycleChecker);
                    Thunker thunker = new Thunker(thunkChecker,cycleChecker);
                    thunker.walk(component_ast);
                    //make sure to do toplevel functions after walking so functional methods and operators will already have thunks
                    TryChecker tryChecker = STypeCheckerFactory.makeTryChecker(componentIndex,traitTable,typeEnv,typeAnalyzer, cycleChecker);
                    com.sun.fortress.scala_src.typechecker.Thunker$.MODULE$.primeFunctionals(componentIndex.parametricOperators(), tryChecker, cycleChecker);
                    com.sun.fortress.scala_src.typechecker.Thunker$.MODULE$.primeFunctionals(componentIndex.functions().secondSet(), tryChecker, cycleChecker);
                    //Typecheck
                    STypeChecker typeChecker =
                        STypeCheckerFactory.make(componentIndex, traitTable, typeEnv, log, typeAnalyzer, cycleChecker);
                    ast = (Component)typeChecker.typeCheck(component_ast);
                    componentIndex = (ComponentIndex)buildIndex(ast, isApi);
                    errors.addAll(Lists.toJavaList(typeChecker.getErrors()));
                    result = new TypeCheckerResult(ast, errors);
                }
                index = componentIndex;
            }
            result.setAst(result.ast().accept(new TypeNormalizer()));
            // There should be no Inference vars left at this point
            if( TypesUtil.assertAfterTypeChecking(result.ast()) )
                bug("Result of typechecking still contains ArrayType/MatrixType/_InferenceVarType.\n" +
                    result.ast());

            errors.addAll(new TypeWellFormedChecker(index, env, typeAnalyzer).check());
            if ( ! errors.isEmpty() ) {
                result = addErrors(errors, result);
                return result;
            }

            // Check overloadings in this compilation unit.
            errors.addAll(new OverloadingChecker(index, env).checkOverloading());
            if ( ! errors.isEmpty() ) {
                result = addErrors(errors, result);
                return result;
            }

            if (!isApi) {
                // Check the set of exported APIs in this component.
                List<? extends StaticError> foo = ExportChecker.checkExports((ComponentIndex)index, env);
                errors.addAll(foo);
                result = addErrors(errors, result);
            }

            return result;
        } else {
            return new TypeCheckerResult(index.ast(), IterUtil.<StaticError>empty());
        }
    }

    private static TypeEnv typeCheckEnv(ComponentIndex component,
                                        GlobalEnvironment env) {
        TypeEnv typeEnv = TypeEnv.make(component);
        // Add all top-level function names to the component-level environment.
        typeEnv = typeEnv.extendWithFunctions(component.functions());
        // Iterate over top-level variables,
        // adding each to the component-level environment.
        typeEnv = typeEnv.extend(component.variables());
        // Add all top-level object names to the component-level environment.
        typeEnv = typeEnv.extendWithTypeConses(component.typeConses());
        return typeEnv;
    }

    private static CompilationUnitIndex buildIndex(CompilationUnit ast, boolean isApi) {
        if (isApi)
            return IndexBuilder.buildCompilationUnitIndex(ast,
                                                          System.currentTimeMillis(),
                                                          isApi);
        else
            return IndexBuilder.buildCompilationUnitIndex(ast,
                                                          System.currentTimeMillis(),
                                                          isApi);
    }

    private static TypeCheckerResult addErrors(List<StaticError> errors,
                                               TypeCheckerResult result) {
        if ( ! errors.isEmpty() ) {
            for ( StaticError error : errors ) {
                result = TypeCheckerResult.addError(result, error);
            }
        }
        return result;
    }

}
