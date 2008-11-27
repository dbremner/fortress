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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sun.fortress.interpreter.env.CUWrapper;
import com.sun.fortress.interpreter.env.ComponentWrapper;
import com.sun.fortress.interpreter.evaluator.types.FTypeTrait;
import com.sun.fortress.interpreter.evaluator.values.FValue;
import com.sun.fortress.interpreter.evaluator.values.OverloadedFunction;
import com.sun.fortress.interpreter.evaluator.values.SingleFcn;
import com.sun.fortress.nodes.APIName;
import com.sun.fortress.nodes.Decl;
import com.sun.fortress.nodes.AliasedAPIName;
import com.sun.fortress.nodes.Api;
import com.sun.fortress.nodes.Component;
import com.sun.fortress.nodes.Id;
import com.sun.fortress.nodes.IdOrOpName;
import com.sun.fortress.nodes.Import;
import com.sun.fortress.nodes.ImportApi;
import com.sun.fortress.nodes.ImportNames;
import com.sun.fortress.nodes.ImportStar;
import com.sun.fortress.nodes.Node;
import com.sun.fortress.nodes.NodeAbstractVisitor;
import com.sun.fortress.nodes.OpName;
import com.sun.fortress.nodes.StaticParam;
import com.sun.fortress.nodes.TraitDecl;
import com.sun.fortress.nodes._RewriteFnOverloadDecl;
import com.sun.fortress.nodes._RewriteObjectExprDecl;
import com.sun.fortress.nodes._RewriteFunctionalMethodDecl;
import com.sun.fortress.nodes_util.NodeUtil;

import edu.rice.cs.plt.tuple.Option;

import static com.sun.fortress.exceptions.InterpreterBug.bug;

public class BuildTopLevelEnvironments extends BuildEnvironments {

    /**
     * Empty by default
     */
    public Set<String> valNames = new HashSet<String>();
    /**
     * Empty by default
     */
    public Set<String> overloadNames = new HashSet<String>();
    /**
     * Empty by default
     */
    public Set<String> typeNames = new HashSet<String>();

    /**
     * Used for mapping API Names to their environments
     */
    Map<String, ComponentWrapper> linker;

    /**
     * Creates an environment builder that will inject bindings into 'within'.
     * The visit is suspended at generics (com.sun.fortress.interpreter.nodes
     * with type parameters) until they can be instantiated.
     */
    public BuildTopLevelEnvironments(Environment within, Map<String, ComponentWrapper> linker) {
        super(within);
        this.linker = linker;
    }

    @Override
    public Boolean forImportApi(ImportApi x) {
        List<AliasedAPIName> apis = x.getApis();
        for (AliasedAPIName aliased_api : apis) {
            APIName imported = aliased_api.getApi();
            importAPIName(imported);
        }
        return null;
    }

  @Override
    public Boolean forImportNames(ImportNames x) {
        APIName imported = x.getApi();
        importAPIName(imported);
        return null;
    }

    @Override
    public Boolean forImportStar(ImportStar x) {
        APIName imported = x.getApi();
        importAPIName(imported);
        return null;
    }

    private void importAPIName(APIName imported) {
        String s = NodeUtil.nameString(imported);
        importAPIName(s);
    }

    public void importAPIName(String s) {
        CUWrapper c = linker.get(s);
        if (c != null)
            bindInto.putApi(s, c.getEnvironment());
        else {
            System.err.println("Reference to missing api/component " + s);
        }
        // Null Pointer Exception here means that static checking was inadequate.
    }


    /*
     * (non-Javadoc)
     *
     * @see com.sun.fortress.interpreter.nodes.NodeVisitor#forApi(com.sun.fortress.interpreter.nodes.Api)
     */
    @Override
    public Boolean forApi(Api x) {
        List<Decl> decls = x.getDecls();

        switch (getPass()) {
        case 1:
        case 2:
        case 3:
        case 4: doDefs(this, decls);break;
        }
        return null;

    }

    public Boolean for_RewriteObjectExprDecl(_RewriteObjectExprDecl x) {
        return null;
    }

    public Boolean for_RewriteFunctionalMethodDecl(_RewriteFunctionalMethodDecl x) {
        return null;
    }

    public Boolean for_RewriteFnOverloadDecl(_RewriteFnOverloadDecl x) {
        switch (getPass()) {
        case 1: {
            // Define the overloaded function.
            String s = x.getName().stringName();
            bindInto.putValue(s,new OverloadedFunction(x.getName(), bindInto));
        }
        break;

        case 3: {
            String s = x.getName().stringName();
            OverloadedFunction of = (OverloadedFunction) bindInto.getRootValue(s);
            for (IdOrOpName fn : x.getFns()) {
                Option<APIName> oapi = fn.getApi();
                FValue oapi_val = null;

                if (fn instanceof Id) {
                    oapi_val = bindInto.getValueNull((Id) fn, Environment.TOP_LEVEL); // top-level reference
                } else if (fn instanceof OpName) {
                    oapi_val = bindInto.getValueNull((OpName) fn, Environment.TOP_LEVEL); // top-level reference
                } else {
                    bug("Unexpected change to AST node hierarchy");
                }

                if (oapi_val == null) {
                    bug("Failed to find overload member " + fn + " for " + x);
                }

                if (oapi_val instanceof SingleFcn) {
                    of.addOverload((SingleFcn) oapi_val, true);
                } else if (oapi_val instanceof OverloadedFunction) {
                    of.addOverloads((OverloadedFunction) oapi_val);
                } else {
                    bug("Unexpected function binding for " + fn +" , value is " + oapi_val);
                }

            }

            of.finishInitializing();

        }

        }
        return null;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.sun.fortress.interpreter.nodes.NodeVisitor#forComponent(com.sun.fortress.interpreter.nodes.Component)
     */
    @Override
    public Boolean forComponent(Component x) {
        List<Decl> defs = x.getDecls();
        switch (getPass()) {
        case 1: forComponent1(x); break;

        case 2: doDefs(this, defs);
        break;
        case 3: {
            ForceTraitFinish v = new ForceTraitFinish() ;
            for (Decl def : defs) {
                v.visit(def);
            }
        }
        doDefs(this, defs);break;
        case 4: doDefs(this, defs);break;
        }
        return null;
    }

    public Boolean forComponentDefs(Component x) {
        List<Decl> defs = x.getDecls();
        doDefs(this, defs);
        return null;
    }

    public Boolean forComponent1(Component x) {
        APIName name = x.getName();
        List<Import> imports = x.getImports();
        // List<Export> exports = x.getExports();
        List<Decl> defs = x.getDecls();

        // SComponent comp = new SComponent(BetterEnv.primitive(x), x);
        //containing.putComponent(name, comp);

        forComponentDefs(x);

        for (Import imp : imports) {
            imp.accept(this);
        }

        return null;
    }

    class ForceTraitFinish extends NodeAbstractVisitor<Boolean> {

        /**
         * Make the default behavior return null, no throw an exception.
         */
        public Boolean defaultCase(Node x) {
            return null;
        }

        /* (non-Javadoc)
         * @see com.sun.fortress.interpreter.nodes.NodeVisitor#forTraitDecl(com.sun.fortress.interpreter.nodes.TraitDecl)
         */
        @Override
        public Boolean forTraitDecl(TraitDecl x) {
            List<StaticParam> staticParams = x.getStaticParams();
            Id name = x.getName();

            if (staticParams.isEmpty()) {
                    FTypeTrait ftt = (FTypeTrait) containing
                            .getRootType(NodeUtil.nameString(name)); // top level
                    Environment interior = ftt.getWithin();
                    ftt.getMembers();
            }
            return null;
        }

        void visit(Decl def) {
            def.accept(this);
        }
    }

    public void setExporterAndApi(CUWrapper exporter, CUWrapper api) {
        bug("Can only set exporter of API environment builder.");
    }


}
