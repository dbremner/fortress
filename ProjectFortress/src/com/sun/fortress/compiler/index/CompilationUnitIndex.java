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

package com.sun.fortress.compiler.index;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.sun.fortress.nodes.APIName;
import com.sun.fortress.nodes.CompilationUnit;
import com.sun.fortress.nodes.Id;
import com.sun.fortress.nodes.IdOrOpOrAnonymousName;
import com.sun.fortress.nodes.Import;
import com.sun.fortress.nodes.ImportedNames;
import com.sun.fortress.nodes.NodeAbstractVisitor_void;

import edu.rice.cs.plt.collect.CollectUtil;
import edu.rice.cs.plt.collect.Relation;

/** Comprises {@link ApiIndex} and {@link CompilationUnit}. */
public abstract class CompilationUnitIndex {

    private final CompilationUnit _ast;
    private final Map<Id, Variable> _variables;
    private final Relation<IdOrOpOrAnonymousName, Function> _functions;
    private final Set<ParametricOperator> _parametricOperators;
    private final Map<Id, TypeConsIndex> _typeConses;
    private final Map<Id, Dimension> _dimensions;
    private final Map<Id, Unit> _units;
    private final long _modifiedDate;

    public CompilationUnitIndex(CompilationUnit ast,
                                Map<Id, Variable> variables,
                                Relation<IdOrOpOrAnonymousName, Function> functions,
                                Set<ParametricOperator> parametricOperators,
                                Map<Id, TypeConsIndex> typeConses,
                                Map<Id, Dimension> dimensions,
                                Map<Id, Unit> units,
                                long modifiedDate) {
        _ast = ast;
        _variables = CollectUtil.immutable(variables);
        _functions = CollectUtil.immutable(functions);
        _parametricOperators = CollectUtil.immutable(parametricOperators);
        _typeConses = CollectUtil.immutable(
                          CollectUtil.union(typeConses, 
                                  CollectUtil.union(dimensions, units)));
        _dimensions = CollectUtil.immutable(dimensions);
        _units = CollectUtil.immutable(units);
        _modifiedDate = modifiedDate;
    }

    public CompilationUnit ast() { return _ast; }

    public abstract Set<APIName> exports();

    public Set<APIName> imports() {
        final Set<APIName> result = new HashSet<APIName>();
        for (Import _import : ast().getImports()) {
            _import.accept(new NodeAbstractVisitor_void() {
                public void forImportedNames(ImportedNames that) {
                    result.add(that.getApiName());
                }
            });
        }
        return result;
    }

    public Map<Id, Variable> variables() { return _variables; }

    public Relation<IdOrOpOrAnonymousName, Function> functions() { return _functions; }
    
    public Set<ParametricOperator> parametricOperators() { return _parametricOperators; }

    public Map<Id, TypeConsIndex> typeConses() { return _typeConses; }
    
    public Map<Id, Dimension> dimensions() { return _dimensions; }

    public Map<Id, Unit> units() { return _units; }

    public long modifiedDate() { return _modifiedDate; }
    
}
