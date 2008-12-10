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

package com.sun.fortress.compiler.typechecker;

import static com.sun.fortress.nodes_util.NodeFactory.makeLValue;
import static edu.rice.cs.plt.tuple.Option.some;
import static com.sun.fortress.exceptions.InterpreterBug.bug;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sun.fortress.compiler.index.DeclaredVariable;
import com.sun.fortress.compiler.index.ParamVariable;
import com.sun.fortress.compiler.index.SingletonVariable;
import com.sun.fortress.compiler.index.Variable;
import com.sun.fortress.nodes.Id;
import com.sun.fortress.nodes.IdOrOpOrAnonymousName;
import com.sun.fortress.nodes.Node;
import com.sun.fortress.nodes.Param;
import com.sun.fortress.nodes.StaticParam;
import com.sun.fortress.nodes.Type;
import com.sun.fortress.nodes._InferenceVarType;
import com.sun.fortress.useful.NI;

import edu.rice.cs.plt.tuple.Option;

/**
 * This environment represents bindings of top-level variables in a component.
 * It consists of a map from Ids to Variables.
 */
class VarTypeEnv extends TypeEnv {
    private Map<Id, Variable> entries;
    private TypeEnv parent;

    VarTypeEnv(Map<Id, Variable> _entries, TypeEnv _parent) {
        entries = _entries;
        parent = _parent;
    }

    /**
     * Return a BindingLookup that binds the given IdOrOpOrAnonymousName to a type
     * (if the given IdOrOpOrAnonymousName is in this type environment).
     */
    public Option<BindingLookup> binding(IdOrOpOrAnonymousName var) {
     if (!(var instanceof Id)) { return parent.binding(var); }
     Id _var = (Id)var;

     Id no_api_var = removeApi(_var);

        if (entries.containsKey(no_api_var)) {
            Variable result = entries.get(no_api_var);
            if (result instanceof DeclaredVariable) {
                return some(new BindingLookup(((DeclaredVariable)result).ast()));
            } else if (result instanceof SingletonVariable) {
                SingletonVariable _result = (SingletonVariable)result;
                Id declaringTrait = _result.declaringTrait();
                return some(new BindingLookup(makeLValue(_var, declaringTrait)));

            } else { // result instanceof ParamVariable
                ParamVariable _result = (ParamVariable)result;
                Param param = _result.ast();
                Option<Type> type = typeFromParam(param);

                return some(new BindingLookup(makeLValue(param.getSpan(), param.getName(),
                                                         param.getMods(), type, false)));
            }
        } else {
            return parent.binding(var);
        }
    }

    @Override
    public List<BindingLookup> contents() {
        List<BindingLookup> result = new ArrayList<BindingLookup>();
        for (IdOrOpOrAnonymousName name : entries.keySet()) {
            Option<BindingLookup> element = binding(name);
            if (element.isSome()) {
                result.add(element.unwrap());
            }
        }
        result.addAll(parent.contents());
        return result;
    }

    @Override
	public Option<Node> declarationSite(IdOrOpOrAnonymousName id) {
	     if (!(id instanceof Id)) { return parent.declarationSite(id); }
	     Id _var = (Id)id;

	     Id no_api_var = removeApi(_var);

	     if (entries.containsKey(no_api_var)) {
	    	 Variable var = entries.get(no_api_var);
	    	 if( var instanceof DeclaredVariable ) {
	    		 return Option.<Node>some(((DeclaredVariable)var).ast());
	    	 }
	    	 else if( var instanceof ParamVariable ) {
	    		 return Option.<Node>some(((ParamVariable)var).ast());
	    	 }
	    	 else if( var instanceof SingletonVariable ) {
	    		 return NI.nyi("We don't yet store the declaring nodes for SingletonVariables.");
	    	 }
	    	 else {
	    		 bug("Unknown subtype of Variable");
	    	 }
	     }
	     return parent.declarationSite(id);
	}

	@Override
	public TypeEnv replaceAllIVars(Map<_InferenceVarType, Type> ivars) {
		Map<Id, Variable> new_entries = new HashMap<Id,Variable>();

		InferenceVarReplacer rep = new InferenceVarReplacer(ivars);

		for( Map.Entry<Id, Variable> entry : this.entries.entrySet() ) {
			Variable v = entry.getValue();

			Variable new_v = v.acceptNodeUpdateVisitor(rep);
			new_entries.put(entry.getKey(), new_v);
		}

		return new VarTypeEnv(new_entries, parent.replaceAllIVars(ivars));
	}

	@Override
	public Option<StaticParam> staticParam(IdOrOpOrAnonymousName id) {
		return this.parent.staticParam(id);
	}
}
