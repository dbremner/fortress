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

package com.sun.fortress.compiler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.sun.fortress.compiler.index.ComponentIndex;
import com.sun.fortress.exceptions.StaticError;
import com.sun.fortress.nodes.APIName;
import com.sun.fortress.nodes.Component;
import com.sun.fortress.nodes.Decl;
import com.sun.fortress.nodes.Id;
import com.sun.fortress.nodes.IdOrOp;
import com.sun.fortress.nodes.Op;
import com.sun.fortress.nodes._RewriteFnOverloadDecl;
import com.sun.fortress.nodes_util.NodeFactory;
import com.sun.fortress.nodes_util.Span;
import com.sun.fortress.repository.ProjectProperties;

public class OverloadRewriter {

    public static class ComponentResult extends StaticPhaseResult {
        private final Map<APIName, ComponentIndex> _components;
        public ComponentResult(Map<APIName, ComponentIndex> components,
                               Iterable<? extends StaticError> errors) {
            super(errors);
            _components = components;
        }
        public Map<APIName, ComponentIndex> components() { return _components; }
    }

    /** Statically check the given components. */
    public static ComponentResult
        rewriteComponents(Map<APIName, ComponentIndex> components,
                          GlobalEnvironment env)
    {
        HashSet<Component> rewrittenComponents = new HashSet<Component>();
        Iterable<? extends StaticError> errors = new HashSet<StaticError>();

        for (APIName componentName : components.keySet()) {
            Component rewrite = rewriteComponent(components.get(componentName), env);
            rewrittenComponents.add(rewrite);
        }
        return new ComponentResult
            (IndexBuilder.buildComponents(rewrittenComponents,
                                          System.currentTimeMillis()).
                 components(), errors);
    }

    public static Component rewriteComponent(ComponentIndex component,
                                             GlobalEnvironment env) {
        Component comp = (Component) component.ast();
        OverloadRewriteVisitor visitor = new OverloadRewriteVisitor();
        comp = (Component) comp.accept(visitor);
        List<Decl> decls = comp.getDecls();
        Span span = comp.getSpan();

        // Add rewritten overloaded functions
        Map<String, List<Id>> overloadedFunctions = visitor.getOverloadedFunctions();
        for (Map.Entry<String, List<Id>> overload : overloadedFunctions.entrySet()) {
            List<IdOrOp> overloadings = new ArrayList<IdOrOp>(overload.getValue());
            Id overloadingId = NodeFactory.makeId(overload.getKey());
            _RewriteFnOverloadDecl newDecl = new _RewriteFnOverloadDecl(span, overloadingId, overloadings);
            decls.add(newDecl);
        }

        // Add rewritten overloaded operators
        Map<String, List<Op>> overloadedOperators = visitor.getOverloadedOperators();
        for (Map.Entry<String, List<Op>> overload : overloadedOperators.entrySet()) {
            List<IdOrOp> overloadings = new ArrayList<IdOrOp>(overload.getValue());
            Op overloadingOp = NodeFactory.makeOp(NodeFactory.makeSpan("impossible", overload.getValue()), overload.getKey());
            _RewriteFnOverloadDecl newDecl = new _RewriteFnOverloadDecl(span, overloadingOp, overloadings);
            decls.add(newDecl);
        }
        return comp;
    }

}
