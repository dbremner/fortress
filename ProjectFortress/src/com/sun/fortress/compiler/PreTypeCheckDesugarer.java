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

import java.util.*;
import com.sun.fortress.compiler.desugarer.*;
import com.sun.fortress.compiler.index.ApiIndex;
import com.sun.fortress.compiler.index.ComponentIndex;
import com.sun.fortress.exceptions.StaticError;
import com.sun.fortress.nodes.Component;
import com.sun.fortress.nodes.Api;
import com.sun.fortress.nodes.APIName;
import com.sun.fortress.nodes.Node;
import com.sun.fortress.nodes_util.Span;
import com.sun.fortress.scala_src.typechecker.IndexBuilder;
import com.sun.fortress.compiler.typechecker.TypeEnv;
import com.sun.fortress.scala_src.typechecker.TraitTable;

import edu.rice.cs.plt.iter.IterUtil;
import edu.rice.cs.plt.tuple.Pair;

/**
 * Performs desugarings of Fortress programs that can be done before type checking.
 * At present, no desugarings are done in this phase, but we keep it as a placeholder
 * for later use. To add a desugaring over a component, go to method desugarComponent
 * and assign to variable comp the result of running your desugaring over comp. If you have
 * written your desugaring as a visitor, simply write:
 *
 * comp = comp.accept(<your visitor>);
 *
 * Assumes all names referring to APIs are fully-qualified,
 * and that the other transformations handled by the {@link com.sun.fortress.compiler.Disambiguator} have
 * been performed.
 */
public class PreTypeCheckDesugarer {

    public static class ApiResult extends StaticPhaseResult {
        Map<APIName, ApiIndex> _apis;

        public ApiResult(Map<APIName, ApiIndex> apis, Iterable<? extends StaticError> errors) {
            super(errors);
            _apis = apis;
        }
        public Map<APIName, ApiIndex> apis() { return _apis; }
    }

    /**
     * Check the given apis. To support circular references, the apis should appear
     * in the given environment.
     */
    public static ApiResult desugarApis(Map<APIName, ApiIndex> apis,
                                        GlobalEnvironment env) {
        HashSet<Api> desugaredApis = new HashSet<Api>();

        for (ApiIndex apiIndex : apis.values()) {
            Api api = desugarApi(apiIndex,env);
            desugaredApis.add(api);
        }
        return new ApiResult
            (IndexBuilder.buildApis(desugaredApis,
                                    env,
                                    System.currentTimeMillis()).apis(),
             IterUtil.<StaticError>empty());
    }

    public static Api desugarApi(ApiIndex apiIndex, GlobalEnvironment env) {
        Api api = (Api)apiIndex.ast();
        return api;
    }

    public static class ComponentResult extends StaticPhaseResult {
        private final Map<APIName, ComponentIndex> _components;
        public ComponentResult(Map<APIName, ComponentIndex> components,
                               Iterable<? extends StaticError> errors) {
            super(errors);
            _components = components;
        }
        public Map<APIName, ComponentIndex> components() { return _components; }
    }

    /** Desugar the given components. */
    public static ComponentResult
        desugarComponents(Map<APIName, ComponentIndex> components,
                          GlobalEnvironment env)
    {
        HashSet<Component> desugaredComponents = new HashSet<Component>();
        Iterable<? extends StaticError> errors = new HashSet<StaticError>();

        for (Map.Entry<APIName, ComponentIndex> component : components.entrySet()) {
            desugaredComponents.add(desugarComponent(component.getValue(), env));
        }
        return new ComponentResult
            (IndexBuilder.buildComponents(desugaredComponents,
                                          System.currentTimeMillis()).
                 components(), errors);
    }

    public static Component desugarComponent(ComponentIndex component,
                                             GlobalEnvironment env) {
        return (Component) component.ast();
    }
}
