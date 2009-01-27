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
package com.sun.fortress.interpreter.env;

import static com.sun.fortress.exceptions.InterpreterBug.bug;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import com.sun.fortress.compiler.NamingCzar;
import com.sun.fortress.compiler.index.ComponentIndex;
import com.sun.fortress.interpreter.evaluator.BuildEnvironments;
import com.sun.fortress.interpreter.evaluator.Environment;
import com.sun.fortress.interpreter.evaluator.types.FTypeGeneric;
import com.sun.fortress.interpreter.evaluator.types.FTypeObject;
import com.sun.fortress.interpreter.evaluator.values.Constructor;
import com.sun.fortress.interpreter.evaluator.values.GenericConstructor;
import com.sun.fortress.interpreter.glue.WellKnownNames;
import com.sun.fortress.interpreter.rewrite.RewriteInPresenceOfTypeInfoVisitor;
import com.sun.fortress.nodes.APIName;
import com.sun.fortress.nodes.Api;
import com.sun.fortress.nodes.CompilationUnit;
import com.sun.fortress.nodes.Component;
import com.sun.fortress.nodes.Param;
import com.sun.fortress.nodes.StaticParam;
import com.sun.fortress.nodes._RewriteObjectExpr;
import com.sun.fortress.nodes_util.Span;
import com.sun.fortress.nodes_util.NodeFactory;
import com.sun.fortress.nodes_util.NodeUtil;
import com.sun.fortress.repository.DerivedFiles;
import com.sun.fortress.repository.IOAst;
import com.sun.fortress.repository.ProjectProperties;
import com.sun.fortress.useful.BASet;
import com.sun.fortress.useful.Fn;
import com.sun.fortress.useful.Useful;

import edu.rice.cs.plt.tuple.Option;

public class ComponentWrapper extends CUWrapper {

    /*
     * Next three lines are for the "cache" of rewritten ASTs
     */
    private static Fn<APIName, String> toCompFileName = new Fn<APIName, String>() {
        @Override
        public String apply(APIName x) {
            return ProjectProperties.compFileName(ProjectProperties.INTERPRETER_CACHE_DIR, NamingCzar.deCaseName(x));
        }
    };
    private static IOAst componentReaderWriter = new IOAst(toCompFileName);
    private static DerivedFiles<CompilationUnit> componentCache =
        new DerivedFiles<CompilationUnit>(componentReaderWriter);

    public static boolean noCache;

    Component transformed;
    boolean cacheDisabled;


    private Component getCached(ComponentIndex comp) {
        if (cacheDisabled)
            return null;
        else
            return  (Component) componentCache.get(comp.ast().getName(), comp.modifiedDate());
    }

    public ComponentWrapper(ComponentIndex comp, HashMap<String, ComponentWrapper> linker,
            String[] implicitLibs) {
        super((Component) comp.ast(), linker, implicitLibs);
        cacheDisabled = noCache;

        transformed = getCached(comp);
        // TODO Auto-generated constructor stub
    }

    public ComponentWrapper(ComponentIndex comp, APIWrapper api,
            HashMap<String, ComponentWrapper> linker, String[] implicitLibs) {
        super((Component) comp.ast(), api, linker, implicitLibs);
        cacheDisabled = noCache;
       transformed = getCached(comp);
        // TODO Auto-generated constructor stub
    }

    /**
     * Reads a "command line" component; do not leave in the cache.
     * @param comp
     * @param api_list
     * @param linker
     * @param implicitLibs
     */
    public ComponentWrapper(ComponentIndex comp, List<APIWrapper> api_list,
            HashMap<String, ComponentWrapper> linker, String[] implicitLibs) {
        super((Component) comp.ast(), api_list, linker, implicitLibs);
        cacheDisabled = noCache;
        transformed = getCached(comp);
    }

    public CompilationUnit populateOne() {
        if (visitState != IMPORTED)
            return bug("Component wrapper " + name() + " in wrong visit state: " + visitState);

        visitState = POPULATED;

        CompilationUnit cu = comp_unit;

        if (transformed == null) {
            cu = (Component) RewriteInPresenceOfTypeInfoVisitor.Only.visit(comp_unit);
            transformed = (Component) desugarer.visit(cu); // Rewrites cu!
            if (!cacheDisabled) {
                componentCache.put(transformed.getName(), transformed);
            }
        }

        if (!cacheDisabled && exportsMain(transformed)) {
            // It's not a library, no point keeping this copy in memory.
            componentCache.forget(transformed.getName());
        }
        cu = transformed;
        be.visit(cu);
        // Reset the non-function names from the disambiguator.
        excludedImportNames = new BASet<String>(com.sun.fortress.useful.StringHashComparer.V);
        be.getEnvironment().visit(nameCollector);
        comp_unit = cu;

        for (String implicitLibraryName : implicitLibs) {
            be.importAPIName(implicitLibraryName);
        }

        for (CUWrapper api: exports.values()) {
            be.importAPIName(api.name());
        }

        for (APIWrapper api: exports.values()) {
            api.populateOne(this);
        }

        return cu;
    }

    private boolean exportsMain(Component transformed2) {
        List<APIName> exports = transformed2.getExports();
        for (APIName a : exports) {
            if (WellKnownNames.exportsMain(a.getText()))
                return true;
        }
        return false;
    }

    /**
     * Adds, to the supplied environment, constructors for any object
     * expressions encountered in the tree(s) processed by this Disambiguator.
     * @param env
     */
    protected void registerObjectExprs(Environment env) {
        Component comp = (Component) comp_unit;

            for (_RewriteObjectExpr oe : NodeUtil.getObjectExprs( comp )) {
                String name = oe.getGenSymName();
                List<StaticParam> params = NodeUtil.getStaticParams(oe);
                Span span = NodeUtil.getSpan(oe);
                if (params.isEmpty()) {
                    // Regular constructor
                    FTypeObject fto = new FTypeObject(name, env, oe, NodeUtil.getParams(oe),
                                                      NodeUtil.getDecls(oe), oe);
                    env.putType(name, fto);
                    BuildEnvironments.finishObjectTrait(NodeUtil.getTypes(NodeUtil.getExtendsClause(oe)),
                                                        null, null, fto, env, oe);
                    Constructor con = new Constructor(env, fto, oe,
                                                      NodeFactory.makeId(span, name),
                                                      NodeUtil.getDecls(oe),
                                                      Option.<List<Param>>none());

                    env.putValue(name, con);
                    con.finishInitializing();
                } else {
                    // Generic constructor
                    FTypeGeneric fto = new FTypeGeneric(env, oe, NodeUtil.getDecls(oe), oe);
                    env.putType(name, fto);
                    GenericConstructor con = new GenericConstructor(env, oe, NodeFactory.makeId(span, name));
                    env.putValue(name, con);
                }
            }

    }
    public Set<String> getTopLevelRewriteNames() {
        return desugarer.getTopLevelRewriteNames();
    }

    public Set<String> getFunctionals() {
        if (transformed != null) {
            return Useful.set(NodeUtil.getFunctionalMethodNames( transformed ));
        }
        return desugarer.functionals;
    }
}
