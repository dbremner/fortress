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

package com.sun.fortress.syntax_abstractions.phases;

import com.sun.fortress.syntax_abstractions.rats.util.FreshName;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedList;
import java.util.ArrayList;

import com.sun.fortress.compiler.GlobalEnvironment;
import com.sun.fortress.compiler.index.ApiIndex;

import com.sun.fortress.exceptions.MacroError;
import com.sun.fortress.useful.Fn;
import com.sun.fortress.useful.Useful;

import com.sun.fortress.nodes_util.NodeFactory;
import com.sun.fortress.nodes_util.NodeUtil;

import com.sun.fortress.nodes.*;
import com.sun.fortress.nodes_util.Span;

import com.sun.fortress.useful.Debug;

import edu.rice.cs.plt.tuple.Option;

/**
 * Macro expander: Traverses AST node, replacing transformation nodes with
 * the results of their transformer executions.
 */
public class Transform extends TemplateUpdateVisitor {
    /* Map of a name to the macro transformer */
    private Map<String,Transformer> transformers;
    /* Map of a template variable to a structure containing its value and
     * its ellipses nesting depth
     */
    private Map<String,Level> variables;
    /* Map of original variable names to their renamed counterparts.
     */
    private SyntaxEnvironment syntaxEnvironment;
    /* rename is true if a macro has been invoked. When it is true
     * variables must be renamed hygienically.
     */
    private boolean rename = false;
    // private EllipsesEnvironment env;

    private Transform( Map<String,Transformer> transformers, Map<String,Level> variables, SyntaxEnvironment syntaxEnvironment, boolean rename ){
        // this( transformers, variables, new EllipsesEnvironment() );
        this.transformers = transformers;
        this.variables = variables;
        this.syntaxEnvironment = syntaxEnvironment;
        this.rename = rename;
    }

    /* entry point to this class */
    public static Node transform(GlobalEnvironment env, Node node){
        Transform transform =
            new Transform(populateTransformers(env), new HashMap<String,Level>(), SyntaxEnvironment.identityEnvironment(), false );
        return node.accept(transform);
    }

    /* Anyone that calls this method should be sure to save the syntax
     * environment on entry to the function and restore the environment
     * on exit. Something like
     * void foo(){
     *      SyntaxEnvironment save = getSyntaxEnvironment();
     *      ...
     *      extendSyntaxEnvironment(...);
     *      ...
     *      setSyntaxEnvironment(save);
     * }
     */
    private void extendSyntaxEnvironment(Id from, Id to){
        syntaxEnvironment = syntaxEnvironment.extend(from, to);
    }

    /* generate a new unique id. a.k.a gensym */
    private Id generateId(Id original){
        return NodeFactory.makeId(original, FreshName.getFreshName(original.getText() + "-g"));
    }

    /*
    public Node forIdOnly(Id that, Option<APIName> api_result) {
        Debug.debug( Debug.Type.SYNTAX, 2, "Looking up id " + that + " in environment " + syntaxEnvironment );
        return syntaxEnvironment.lookup(that);
    }
    */

    private SyntaxEnvironment getSyntaxEnvironment(){
        return this.syntaxEnvironment;
    }

    private void setSyntaxEnvironment( SyntaxEnvironment e ){
        this.syntaxEnvironment = e;
    }

    public Node forVarRef(VarRef that){
        Option<Type> exprType_result = recurOnOptionOfType(that.getExprType());
        Id var_result = syntaxEnvironment.lookup(that.getVarId());
        Debug.debug(Debug.Type.SYNTAX, 2, "Looking up var ref " + that.getVarId() + " in hygiene environment = " + var_result);
        return forVarRefOnly(that, exprType_result, var_result, that.getStaticArgs());
    }

    /*
    public Node forTemplateGapVarRef(TemplateGapVarRef that) {
        return forVarRef((VarRef) super.forTemplateGapVarRef(that));
    }
    */

    /* Support renaming for these nodes
     * LValue  ( only for LocalVarDecl ) - done
     * FnDecl  ( only for local function decls ) - done
     * Param   ( only for FnExpr and local function decls ) - done
     * Label - done
     * Typecase - done
     * Catch - done
     * GeneratorClause - all the following nodes can contain GeneratorClause
     *    TestDecl
     *    For - done
     *    While - done
     *    Accumulator -
     *    ArrayComprehensionClause
     *    IfClause - done
     */

    /* this should only be called by a method that has a GeneratorClause */
    private GeneratorClause handleGeneratorClause(GeneratorClause that){
        List<Id> newIds = Useful.applyToAll(that.getBind(), new Fn<Id,Id>(){
            public Id apply(Id value){
                Id gen = generateId(value);
                extendSyntaxEnvironment(value, gen);
                return gen;
            }
        });
        Expr init_result = (Expr) recur(that.getInit());
        return new GeneratorClause(NodeFactory.makeSpan(newIds, init_result), newIds, init_result);
    }

    public Node forIfClause(IfClause that) {
        if ( rename ){
            SyntaxEnvironment save = getSyntaxEnvironment();
            GeneratorClause test_result = handleGeneratorClause(that.getTestClause());
            Block body_result = (Block) recur(that.getBody());
            setSyntaxEnvironment(save);
            return forIfClauseOnly(that, test_result, body_result);
        } else {
            return super.forIfClause(that);
        }
    }

    public Node forFor(For that) {
        if ( rename ){
            SyntaxEnvironment save = getSyntaxEnvironment();
            Option<Type> exprType_result = recurOnOptionOfType(that.getExprType());
            List<GeneratorClause> gens_result = Useful.applyToAll(that.getGens(), new Fn<GeneratorClause, GeneratorClause>(){
                public GeneratorClause apply(GeneratorClause value){
                    return handleGeneratorClause(value);
                }
            });
            Block body_result = (Block) recur(that.getBody());
            setSyntaxEnvironment(save);
            return forForOnly(that, exprType_result, gens_result, body_result);
        } else {
            return super.forFor(that);
        }
    }

    public Node forWhile(While that) {
        if ( rename ){
            SyntaxEnvironment save = getSyntaxEnvironment();
            Option<Type> exprType_result = recurOnOptionOfType(that.getExprType());
            GeneratorClause test_result = handleGeneratorClause(that.getTestExpr());
            Do body_result = (Do) recur(that.getBody());
            setSyntaxEnvironment(save);
            return forWhileOnly(that, exprType_result, test_result, body_result);
        } else {
            return super.forWhile(that);
        }
    }

    public Node forCatch(Catch that) {
        if ( rename ){
            SyntaxEnvironment save = getSyntaxEnvironment();
            Id name_result = (Id) recur(that.getName());
            Id newId = generateId(name_result);
            extendSyntaxEnvironment(name_result, newId);
            List<CatchClause> clauses_result = recurOnListOfCatchClause(that.getClauses());
            setSyntaxEnvironment(save);
            return forCatchOnly(that, newId, clauses_result);
        } else {
            return super.forCatch(that);
        }
    }

    public Node forExit(Exit that) {
        if ( rename ){
            Option<Type> exprType_result = recurOnOptionOfType(that.getExprType());
            Option<Id> target_result = recurOnOptionOfId(that.getTarget());
            if ( target_result.isSome() ){
                target_result = Option.some(syntaxEnvironment.lookup(target_result.unwrap()));
            }
            Option<Expr> returnExpr_result = recurOnOptionOfExpr(that.getReturnExpr());
            return forExitOnly(that, exprType_result, target_result, returnExpr_result);
        } else {
            return super.forExit(that);
        }
    }

    public Node forLabel(Label that) {
        if ( rename ){
            SyntaxEnvironment save = getSyntaxEnvironment();
            Option<Type> exprType_result = recurOnOptionOfType(that.getExprType());
            Id name_result = (Id) recur(that.getName());
            Id newId = generateId(name_result);
            extendSyntaxEnvironment(name_result, newId);
            Block body_result = (Block) recur(that.getBody());
            setSyntaxEnvironment(save);
            return new Label(NodeFactory.makeSpan(that), exprType_result, newId, body_result);
        } else {
            return super.forLabel(that);
        }
    }

    public Node forTypecase(Typecase that) {
        if ( rename ){
            SyntaxEnvironment save = getSyntaxEnvironment();
            Option<Type> exprType_result = recurOnOptionOfType(that.getExprType());
            // List<Id> bindIds_result = recurOnListOfId(that.getBindIds());
            List<Id> newIds = Useful.applyToAll(that.getBindIds(), new Fn<Id,Id>(){
                public Id apply(Id value){
                    Id gen = generateId(value);
                    extendSyntaxEnvironment(value, gen);
                    return gen;
                }
            });
            Option<Expr> bindExpr_result = recurOnOptionOfExpr(that.getBindExpr());
            List<TypecaseClause> clauses_result = recurOnListOfTypecaseClause(that.getClauses());
            Option<Block> elseClause_result = recurOnOptionOfBlock(that.getElseClause());
            setSyntaxEnvironment(save);
            return new Typecase(NodeFactory.makeSpan(that), exprType_result, newIds, bindExpr_result, clauses_result, elseClause_result);
        } else {
            return super.forTypecase(that);
        }
    }

    public Node forFnExpr(FnExpr that) {
        if ( rename ){
            final Transform transformer = this;
            SyntaxEnvironment save = getSyntaxEnvironment();
            Option<Type> exprType_result = recurOnOptionOfType(that.getExprType());
            IdOrOpOrAnonymousName name_result = (IdOrOpOrAnonymousName) recur(that.getName());
            List<StaticParam> staticParams_result = recurOnListOfStaticParam(that.getStaticParams());
            List<Param> params_result = Useful.applyToAll(that.getParams(), renameParam);
            Option<Type> returnType_result = recurOnOptionOfType(that.getReturnType());
            Option<WhereClause> where_result = recurOnOptionOfWhereClause(that.getWhereClause());
            Option<List<BaseType>> throwsClause_result = recurOnOptionOfListOfBaseType(that.getThrowsClause());
            Expr body_result = (Expr) recur(that.getBody());
            Node ret = forFnExprOnly(that, exprType_result, name_result, staticParams_result, params_result, returnType_result, where_result, throwsClause_result, body_result);
            setSyntaxEnvironment(save);
            return ret;
        } else {
            return super.forFnExpr(that);
        }
    }

    /* rename an id and extend the syntax environment only if the id was
     * not a template parameter
     */
    private Id renameId(Id id){
        return (Id) id.accept( new TemplateUpdateVisitor(){
            public Node forTemplateGapId(TemplateGapId tid){
                return tid.accept(Transform.this);
            }

            public Node forId(Id id){
                Id old = (Id) id.accept(Transform.this);
                Id generatedId = generateId(old);
                Debug.debug( Debug.Type.SYNTAX, 2, "Generate new binding for " + old + " = " + generatedId );
                extendSyntaxEnvironment(old, generatedId);
                return generatedId;
            }
        });
    }

    private Fn<Param,Param> renameParam = new Fn<Param,Param>(){
        public Param apply(Param value){
            final Transform transformer = Transform.this;
            return (Param) value.accept( new TemplateUpdateVisitor(){
                public Node forParamOnly(Param that,
                                         Id name_result, List<Modifier> mods_result,
                                         Option<Type> type_result,
                                         Option<Expr> defaultExpr_result,
                                         Option<Type> varargsType_result) {
                    if ( ! NodeUtil.isVarargsParam(that) ) {
                        Debug.debug( Debug.Type.SYNTAX, 2, "Normal param id hash code " + name_result.generateHashCode() );
                        Id generatedId = renameId(name_result);
                        /*
                          Id old = (Id) name_result.accept(transformer);
                          Id generatedId = generateId(old);
                          Debug.debug( Debug.Type.SYNTAX, 2, "Generate new binding for " + old + " = " + generatedId );
                          extendSyntaxEnvironment(old, generatedId);
                        */
                        return new Param(that.getSpan(), generatedId, mods_result, type_result, defaultExpr_result);
                    } else {
                        Debug.debug( Debug.Type.SYNTAX, 2, "Varargs param id hash code " + name_result.generateHashCode() );
                        Id old = (Id) name_result.accept(transformer);
                        Id generatedId = generateId(old);
                        Debug.debug( Debug.Type.SYNTAX, 2, "Generate new binding for " + old + " = " + generatedId );
                        extendSyntaxEnvironment(old, generatedId);
                        return new Param(that.getSpan(), generatedId, mods_result, Option.<Type>none(), Option.<Expr>none(),
                                         varargsType_result);
                    }
                }
            });
        }
    };

    public Node forLetFn(LetFn that) {
        if ( rename ){
            final Transform transformer = this;
            SyntaxEnvironment save = getSyntaxEnvironment();
            Option<Type> exprType_result = recurOnOptionOfType(that.getExprType());
            List<Expr> body_result = recurOnListOfExpr(that.getBody());
            List<FnDecl> fns_result = Useful.applyToAll(that.getFns(), new Fn<FnDecl, FnDecl>(){
                public FnDecl apply(FnDecl fn){
                    return (FnDecl) fn.accept( new TemplateUpdateVisitor(){
                            public Node forFnDeclOnly(FnDecl that, List<Modifier> mods_result, IdOrOpOrAnonymousName name_result, List<StaticParam> staticParams_result, List<Param> params_result, Option<Type> returnType_result, Option<List<BaseType>> throwsClause_result, Option<WhereClause> where_result, Option<Contract> contract_result, Option<Expr> body_result) {
                                List<Param> new_params_result = Useful.applyToAll(params_result, renameParam);
                                if ( name_result instanceof Id) {
                                    Id old = (Id) ((Id)name_result).accept(transformer);
                                    Id generatedId = generateId(old);
                                    extendSyntaxEnvironment(old, generatedId);
                                    return NodeFactory.makeFnDecl(that.getSpan(),
                                                                  mods_result,
                                                                  generatedId,
                                                                  staticParams_result,
                                                                  new_params_result,
                                                                  returnType_result,
                                                                  throwsClause_result,
                                                                  where_result,
                                                                  contract_result,
                                                                  body_result);
                                } else {
                                    return NodeFactory.makeFnDecl(that.getSpan(),
                                                                  mods_result,
                                                                  name_result,
                                                                  staticParams_result,
                                                                  new_params_result,
                                                                  returnType_result,
                                                                  throwsClause_result,
                                                                  where_result,
                                                                  contract_result,
                                                                  body_result);
                                }
                            }
                        });
                }
            });
            Node ret = forLetFnOnly(that, exprType_result, body_result, fns_result);
            setSyntaxEnvironment(save);
            return ret;
        } else {
            return super.forLetFn(that);
        }
    }

    public Node forLocalVarDecl(LocalVarDecl that) {
        if ( rename ){
            final Transform transformer = this;
            SyntaxEnvironment save = getSyntaxEnvironment();
            Option<Type> exprType_result = recurOnOptionOfType(that.getExprType());
            Debug.debug( Debug.Type.SYNTAX, 2, "Transforming local var decl" );
            List<LValue> lhs_result = Useful.applyToAll(that.getLhs(), new Fn<LValue, LValue>(){
                public LValue apply(LValue value){
                    return (LValue) value.accept( new TemplateUpdateVisitor(){
                        public Node forLValueOnly(LValue that, Id name_result,
                                                  List<Modifier> mods_result,
                                                  Option<Type> type_result) {
                            Id old = (Id) name_result.accept(transformer);
                            Id generatedId = generateId(old);
                            Debug.debug( Debug.Type.SYNTAX, 2, "Generate new binding for " + old + " = " + generatedId );
                            extendSyntaxEnvironment(old, generatedId);
                            return new LValue(that.getSpan(), generatedId, mods_result, type_result);
                        }
                    });
                }
            });
            Option<Expr> rhs_result = recurOnOptionOfExpr(that.getRhs());
            List<Expr> body_result = recurOnListOfExpr(that.getBody());
            /*
            List<Expr> body_result = Useful.applyToAll(recurOnListOfExpr(that.getBody()), new Fn<Expr, Expr>(){
                public Expr apply(Expr value){
                    return value.accept( new NodeUpdateVisitor(){
                        public Node forIdOnly(Id that, Option<APIName> api_result) {
                            Debug.debug( Debug.Type.SYNTAX, 2, "Looking up id " + that + " in environment " + syntaxEnvironment );
                            return Transform.this.syntaxEnvironment.lookup(that);
                        }
                    });
                }
            });
            */
            Node ret = forLocalVarDeclOnly(that, exprType_result, body_result, lhs_result, rhs_result);
            setSyntaxEnvironment(save);
            return ret;
        } else {
            return super.forLocalVarDecl(that);
        }
    }

    private static Map<String,Transformer> populateTransformers(GlobalEnvironment env){
        final Map<String,Transformer> map = new HashMap<String,Transformer>();
        for (ApiIndex api : env.apis().values()){
            api.ast().accept( new NodeDepthFirstVisitor_void(){
                    @Override public void forNamedTransformerDef(NamedTransformerDef that) {
                        map.put(that.getName(), that.getTransformer());
                    }
		});
        }
        return map;
    }

    private Transformer lookupTransformer( String name ){
        if ( transformers.get( name ) == null ){
            throw new MacroError( "Cannot find transformer for " + name );
        }
        return transformers.get( name );
    }

    private Node curry( String original, Map<String,Level> vars,
			List<String> parameters ){
        return new CurriedTransformer(original, vars, parameters);
    }

    private boolean hasVariable( Id id ){
        return this.variables.get(id.getText()) != null;
    }

    private int lookupLevel(Id id){
        String variable = id.getText();
        Level binding = this.variables.get(variable);
        if ( binding == null ){
            throw new MacroError( "Can't find a binding for gap " + id );
        }
        return binding.getLevel();
    }

    private Level lookupVariable(Id id, List<Id> params){
        String variable = id.getText();
        Level binding = this.variables.get(variable);
        if ( binding == null ){
                throw new MacroError( "Can't find a binding for gap " + id );
        } else {
            if ( params.isEmpty() ){
                /*
                if ( binding instanceof List ){
                    return new _RepeatedExpr((List) binding);
                }
                */
                Debug.debug( Debug.Type.SYNTAX, 2, "Found template gap " + binding.get_object() );
                return binding;
            } else {

                if ( ! (binding.get_object() instanceof CurriedTransformer) ){
                    throw new MacroError("Parameterized template gap is not bound " +
					 "to a CurriedTransformer, instead bound to " +
					 binding.getClass().getName());
                }

                CurriedTransformer curried = (CurriedTransformer) binding.get_object();
                Map<String,Level> vars = new HashMap<String,Level>( curried.getVariables() );
                if ( curried.getSyntaxParameters().size() != params.size() ){
                    throw new MacroError( "Passing " + params.size() +
					  " arguments to a nonterminal that accepts " +
					  curried.getSyntaxParameters().size() + " arguments." );
                }

                Debug.debug( Debug.Type.SYNTAX, 3,
			     "Template gap " + id.getText() + " has parameters " + params );
                for ( int i = 0; i < params.size(); i++ ){
                    Id parameter = params.get( i );
                    String name = curried.getSyntaxParameters().get( i );
                    Debug.debug( Debug.Type.SYNTAX, 3, "Adding parameter " + name );
                    vars.put( name, lookupVariable(parameter, new LinkedList<Id>()) );
                }
                // return curry((Node)binding, vars);

                /* the type of the transformer doesn't matter */
		Node newNode =
		    new _SyntaxTransformationExpr(new Span(), curried.getSyntaxTransformer(),
						  vars, new LinkedList<String>());
                return new Level(binding.getLevel(), newNode.accept( this ) );
            }
        }
    }

    @Override public Node forTemplateGapOnly(TemplateGap that, Id gapId_result,
					     List<Id> templateParams_result) {
        /* another annoying cast */
        Debug.debug( Debug.Type.SYNTAX, 3, "Looking up gapid " + gapId_result );
        // Node n = ((Node) lookupVariable(gapId_result, templateParams_result).get_object()).accept(this);
        Node n = ((Node) lookupVariable(gapId_result, templateParams_result).get_object());
        // Debug.debug( Debug.Type.SYNTAX, 3, "Hash code for " + n + " is " + n.generateHashCode() );
        Debug.debug( Debug.Type.SYNTAX, 3, "Result for gapid " + gapId_result + " is " + n.getClass() + " " + n );
        return n;
    }

    /*
    @Override public Node for_CurriedTransformer(_CurriedTransformer that) {
        AbstractNode node = that.getOriginal();
        if ( node instanceof _SyntaxTransformation ){
            _SyntaxTransformation syntax = (_SyntaxTransformation) node;
            Map<String,Object> newvars = new HashMap<String,Object>( syntax.getVariables() );
            List<String> vars = new LinkedList<String>();
            / * fill vars in the order that the transformation wants them * /

            for ( String var : vars ){
                newvars.put( var, that.getVariables().get( var ) );
            }
            return new _SyntaxTransformationExpr( new Span(), syntax.getSyntaxTransformer(), newvars ).accept( this );
        } else {
            return node;
        }
    }
    */

    class TransformerEvaluator extends NodeDepthFirstVisitor<Node> {
	private Map<String,Transformer> transformers;
	private Map<String,Level> variables;
        // private EllipsesEnvironment ellipsesEnv;

	private TransformerEvaluator(Map<String,Transformer> transformers,
				     Map<String,Level> variables ){
	    this.transformers = transformers;
	    this.variables = variables;
	}

        private Level lookupVariable( Id name ){
            Level obj = variables.get( name.getText() );
            if ( obj == null ){
                throw new MacroError( "Can't find a binding for gap " + name );
            } else if ( obj.get_object() instanceof CurriedTransformer ){
                throw new MacroError( name + " cannot accept parameters in a case expression" );
            }
            Debug.debug( Debug.Type.SYNTAX, 3, "Looking up variable in transformer evaluator: " + name + " and found " + obj );
            return obj;
        }

	@Override public Node forNodeTransformer(NodeTransformer that) {
	    return that.getNode().accept(new Transform(transformers, variables, SyntaxEnvironment.identityEnvironment(), true));
	    // return that.getNode().accept(new Transform(transformers, variables, Transform.this.syntaxEnvironment, true));
	}

	@Override public Node forCaseTransformer(CaseTransformer that) {
	    Id gapName = that.getGapName();
	    List<CaseTransformerClause> clauses = that.getClauses();

	    // Object toMatch = lookupVariable(gapName, new LinkedList<Id>());
	    Level toMatch = lookupVariable(gapName);
	    for (CaseTransformerClause clause : clauses) {
		Option<Node> result = matchClause(clause, toMatch);
		if (result.isSome()) {
		    return result.unwrap();
		}
		// else continue;
	    }
	    throw new MacroError(that, "match failed");
	}

	private Option<Node> matchClause(CaseTransformerClause clause, Level toMatch) {
	    String constructor = clause.getConstructor().getText();
	    List<Id> parameters = clause.getParameters();
	    int parameterCount = parameters.size();
	    Transformer body = clause.getBody();

	    if (!(constructor.equals("Cons") && parameterCount == 2) &&
		!(constructor.equals("Empty") && parameterCount == 0)) {
		// Nothing else implemented yet
		throw new MacroError(clause.getConstructor(),
                                     "bad case transformer constructor: " + constructor);
	    }

	    if (toMatch.get_object() instanceof List) {
		List<?> list = (List<?>)toMatch.get_object();
                Debug.debug( Debug.Type.SYNTAX, 2, "Matching Cons constructor to list " + list );
		if (constructor.equals("Cons")) {
		    if (!list.isEmpty()) {
			Object first = list.get(0);
			Object rest = list.subList(1, list.size());
			String firstParam = parameters.get(0).getText();
			String restParam = parameters.get(1).getText();
			Map<String, Level> newEnv = new HashMap<String,Level>(variables);
			// TODO not confident these spans are correct.
			newEnv.put(firstParam, new Level(toMatch.getLevel() - 1, first) );
			newEnv.put(restParam, new Level(toMatch.getLevel(), rest) );
                        // EllipsesEnvironment env2 = new EllipsesEnvironment( ellipsesEnv );
                        // env2.add( NodeFactory.makeId( restParam ), 1, rest );
                        Debug.debug( Debug.Type.SYNTAX, 2, "Head of cons variable " + firstParam + " is " + first );
                        Debug.debug( Debug.Type.SYNTAX, 2, "Tail of cons variable " + restParam + " is " + rest );
			return Option.wrap(body.accept(new TransformerEvaluator(transformers, newEnv)));
		    }
		} else if (constructor.equals("Empty")) {
		    if (list.isEmpty()) {
			return Option.wrap(body.accept(this));
		    }
		}
	    }
	    return Option.<Node>none();
	}
    }

    private Object traverse( Object partial ){
        Debug.debug( Debug.Type.SYNTAX, 2, "Traversing object " + partial.getClass().getName() );
        if ( partial instanceof Level ){
            Level l = (Level) partial;
            return new Level(l.getLevel(), traverse( l.get_object() ) );
        } else if ( partial instanceof List ){
            List<Object> all = new LinkedList<Object>();
            for ( Object o : (List<?>) partial ){
                if ( o instanceof _Ellipses ){
                    all.addAll( (List<?>) traverse( o ) );
                } else {
                    all.add( traverse( o ) );
                }
            }
            return all;
        } else if ( partial instanceof _Ellipses ){
            return traverse( handleEllipses( (_Ellipses) partial ) );
        } else if ( partial instanceof Node ){
            return ((Node) partial).accept( this );
        }
        throw new MacroError( "Unknown object type " + partial.getClass().getName() + " value: " + partial );
    }

    @Override public Node defaultTransformationNodeCase(_SyntaxTransformation that) {
        if ( ! that.getSyntaxParameters().isEmpty() ){
	    /* needs parameters, curry it! */
            return curry( that.getSyntaxTransformer(),
			  that.getVariables(),
			  that.getSyntaxParameters() );
        }
        /*
	  Debug.debug(Debug.Type.SYNTAX, 1,
	              "Run transformation on " + that + " is " +
		      that.getSyntaxTransformer().invoke());
	  return that.getSyntaxTransformer().invoke().accept( this );
	*/
        Debug.debug( Debug.Type.SYNTAX, 1, "Run transformation " + that.getSyntaxTransformer() );
        Transformer transformer = lookupTransformer( that.getSyntaxTransformer() );
        //Debug.debug( Debug.Type.SYNTAX, 1,
        //             "Transformation is " + FortressAstToConcrete.astToString(transformer));
        // EllipsesEnvironment env = new EllipsesEnvironment();
        Map<String,Level> arguments = that.getVariables();
        Map<String,Level> evaluated = new HashMap<String,Level>();
        for ( Map.Entry<String,Level> var : arguments.entrySet() ){
            String varName = var.getKey();
            // Node argument = ((Node)var.getValue()).accept(this);
            /*
            if ( var.getValue() instanceof List ){
                Debug.debug( Debug.Type.SYNTAX, 2, "Adding repeated node " + varName );
                env.add( NodeFactory.makeId( varName ), 1, var.getValue() );
            }
            */
            // Level level = var.getValue();
            // Object argument = traverse( level.get_object() );

            /* argh, this cast shouldn't be needed */
            Level argument = (Level) traverse( var.getValue() );

            /* this is almost definately in the wrong place */
            /*
            if ( argument instanceof List ){
                Debug.debug( Debug.Type.SYNTAX, 2, "Adding repeated node " + varName );
                env.add( NodeFactory.makeId( varName ), 1, argument );
            }
            */

            // checkFullyTransformed(argument);
            // evaluated.put(varName, new Level( level.getLevel(), argument ) );
            evaluated.put(varName, argument);
            Debug.debug( Debug.Type.SYNTAX, 3,
			 "Argument " + varName + " is " + argument);
        }

        Debug.debug( Debug.Type.SYNTAX, "Invoking transformer " +
		     that.getSyntaxTransformer() );
        Node transformed =
	    transformer.accept(new TransformerEvaluator(this.transformers, evaluated) );
        checkFullyTransformed(transformed);
        return transformed;
    }

    @Override public List<Expr> recurOnListOfExpr(List<Expr> that) {
        List<Expr> accum = new java.util.ArrayList<Expr>(that.size());
        for (Expr elt : that) {
            if ( elt instanceof _Ellipses ){
                for ( Node n : handleEllipses((_Ellipses) elt) ){
                    accum.add( (Expr) n );
                }
            } else {
                accum.add((Expr) recur(elt));
            }
        }
        return accum;
    }

    private void checkFullyTransformed(Node n) {
        n.accept(new TemplateNodeDepthFirstVisitor_void() {
                @Override public void forTemplateGapOnly(TemplateGap that) {
                    throw new MacroError("Transformation left over template gap: " +
					 that);
                }
                @Override public void for_SyntaxTransformationOnly(_SyntaxTransformation that) {
                    throw new MacroError("Transformation left over transformation application: "
                                               + that);
                }
            });
    }

    /* find the first length of some repeated pattern variable */
    private int findRepeatedVar( List<Id> freeVariables ){
        // for ( Id var : env.getVars() ){
        for ( Id var : freeVariables ){
            if ( lookupLevel( var ) > 0 ){
                int size = ((List) lookupVariable( var, new LinkedList<Id>() ).get_object()).size();
                Debug.debug( Debug.Type.SYNTAX, 2, "Repeated variable " + var + " size is " + size );
                return size;
            }
        }
        throw new MacroError( "No repeated variables!" );
    }

    /* convert an environment into a list of environments, one for each value in the list
     * of values
     */
    private List<Transform> decompose( List<Id> freeVariables ){
        List<Transform> all = new ArrayList<Transform>();

        Debug.debug( Debug.Type.SYNTAX, 2, "Free variables in the decomposed list: " + freeVariables );

        int repeats = findRepeatedVar( freeVariables );
        for ( int i = 0; i < repeats; i++){
            Map<String,Level> newVars = new HashMap<String,Level>();

            for ( Id var : freeVariables ){
                Level value = lookupVariable( var, new LinkedList<Id>() );
                if ( value.getLevel() == 0 ){
                    newVars.put( var.getText(), new Level(value.getLevel(), value ) );
                } else {
                    List l = (List) value.get_object();
                    newVars.put( var.getText(), new Level(value.getLevel() - 1, l.get( i ) ) );
                }
            }

            all.add( new Transform( transformers, newVars, this.syntaxEnvironment, true ) );
        }

        return all;
    }

    private List<Id> freeVariables( Node node ){
        final List<Id> vars = new ArrayList<Id>();

        node.accept( new NodeDepthFirstVisitor_void(){
            @Override
            public void defaultTemplateGap(TemplateGap t){
                vars.add( t.getGapId() );
            }
        });

        return vars;
    }

    private boolean controllable( _Ellipses that ){
        for ( Id var : freeVariables( that.getRepeatedNode() ) ){
            if ( hasVariable( var ) && lookupLevel( var ) > 0 ){
                return true;
            }
        }
        return false;
    }

    private List<Node> handleEllipses( _Ellipses that ){
        if ( controllable( that ) ){
            List<Node> nodes = new ArrayList<Node>();
            // Debug.debug( Debug.Type.SYNTAX, 2, "Original env " + env );
            for ( Transform newEnv : decompose( freeVariables( that.getRepeatedNode() ) ) ){
                // Debug.debug( Debug.Type.SYNTAX, 2, "Decomposed env ", newEnv );
                // nodes.add( that.getRepeatedNode().accept( new EllipsesVisitor( newEnv ) ) );
                /*
                if ( that.getRepeatedNode() instanceof _RepeatedExpr ){
                    nodes.addAll( ((_RepeatedExpr) that.getRepeatedNode()).getNodes() );
                } else {
                */
                /*
                Map<String, Object> variableEnv = new HashMap< String, Object >( variables );
                for ( Id var : newEnv.getVars() ){
                    variableEnv.put( var.getText(), newEnv.getValue( var ) );
                }
                */
                nodes.add( that.getRepeatedNode().accept( newEnv ) );
                // }
            }
            return nodes;
        } else {
            throw new MacroError( "Invalid ellipses expression: " + that );
        }
    }

    private class CurriedTransformer implements Node {
        private String original;
        private Map<String,Level> vars;
        private List<String> parameters;

        public CurriedTransformer( String original, Map<String,Level> vars,
				   List<String> parameters ){
            this.original = original;
            this.vars = vars;
            this.parameters = parameters;
        }

        public String getSyntaxTransformer(){
            return original;
        }

        public Map<String,Level> getVariables(){
            return vars;
        }

        public List<String> getSyntaxParameters(){
            return parameters;
        }

        private Object error(){
            throw new MacroError("Dont call this method");
        }

        public Span getSpan(){
            error();
            return null;
        }

        public String at(){
            error();
            return null;
        }

        public String stringName(){
            error();
            return null;
        }

        public <RetType> RetType accept(NodeVisitor<RetType> visitor){
            error();
            return null;
        }

        public void accept(NodeVisitor_void visitor){
            error();
        }

        public int generateHashCode(){
            error();
            return 0;
        }

        public java.lang.String serialize(){
            error();
            return null;
        }

        public void serialize(java.io.Writer writer){
            error();
        }

        public void outputHelp(TabPrintWriter writer, boolean lossless){
            error();
        }
    }
}
