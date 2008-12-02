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

import java.util.Set;
import edu.rice.cs.plt.tuple.Option;

import com.sun.fortress.nodes.Id;
import com.sun.fortress.nodes.IdOrOp;
import com.sun.fortress.nodes.Op;
import com.sun.fortress.nodes.APIName;
import com.sun.fortress.nodes.StaticParam;
import com.sun.fortress.compiler.index.TypeConsIndex;

public abstract class DelegatingTypeNameEnv extends TypeNameEnv {
    private TypeNameEnv _parent;

    protected DelegatingTypeNameEnv(TypeNameEnv parent) {
        _parent = parent;
    }

    public Option<APIName> apiName(APIName name) {
        return _parent.apiName(name);
    }

    public Option<StaticParam> hasTypeParam(IdOrOp name) {
        return _parent.hasTypeParam(name);
    }

    public Set<Id> explicitTypeConsNames(Id name) {
        return _parent.explicitTypeConsNames(name);
    }

    public Set<Id> onDemandTypeConsNames(Id name) {
        return _parent.onDemandTypeConsNames(name);
    }

    public boolean hasQualifiedTypeCons(Id name) {
        return _parent.hasQualifiedTypeCons(name);
    }

    public TypeConsIndex typeConsIndex(Id name) {
        return _parent.typeConsIndex(name);
    }

    public String toString() {
        return _parent.toString();
    }

}
