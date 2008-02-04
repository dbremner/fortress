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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import com.sun.fortress.compiler.GlobalEnvironment;
import com.sun.fortress.compiler.StaticError;
import com.sun.fortress.compiler.StaticPhaseResult;
import com.sun.fortress.compiler.disambiguator.NameEnv;
import com.sun.fortress.compiler.disambiguator.NonterminalNameDisambiguator;
import com.sun.fortress.compiler.disambiguator.ProductionEnv;
import com.sun.fortress.compiler.index.ApiIndex;
import com.sun.fortress.compiler.index.GrammarIndex;
import com.sun.fortress.compiler.index.ProductionIndex;
import com.sun.fortress.nodes.APIName;
import com.sun.fortress.nodes.Api;
import com.sun.fortress.nodes.GrammarDef;
import com.sun.fortress.nodes.Id;
import com.sun.fortress.nodes.ItemSymbol;
import com.sun.fortress.nodes.KeywordSymbol;
import com.sun.fortress.nodes.NoWhitespaceSymbol;
import com.sun.fortress.nodes.Node;
import com.sun.fortress.nodes.NodeUpdateVisitor;
import com.sun.fortress.nodes.NonterminalDecl;
import com.sun.fortress.nodes.NonterminalSymbol;
import com.sun.fortress.nodes.PrefixedSymbol;
import com.sun.fortress.nodes.QualifiedIdName;
import com.sun.fortress.nodes.SyntaxDef;
import com.sun.fortress.nodes.SyntaxSymbol;
import com.sun.fortress.nodes.TokenSymbol;
import com.sun.fortress.nodes.WhitespaceSymbol;
import com.sun.fortress.nodes_util.NodeFactory;
import com.sun.fortress.nodes_util.NodeUtil;
import com.sun.fortress.nodes_util.Span;
import com.sun.fortress.parser_util.IdentifierUtil;
import com.sun.fortress.useful.HasAt;

import edu.rice.cs.plt.collect.CollectUtil;
import edu.rice.cs.plt.tuple.Option;

public class ItemDisambiguator extends NodeUpdateVisitor {

	/** Result of {@link #disambiguateApis}. */
	public static class ApiResult extends StaticPhaseResult {
		private final Iterable<Api> _apis;

		public ApiResult(Iterable<Api> apis, 
				Iterable<? extends StaticError> errors) {
			super(errors);
			_apis = apis;
		}

		public Iterable<Api> apis() { return _apis; }
	}

	private Collection<StaticError> _errors;
	private GlobalEnvironment _globalEnv;
	private GrammarIndex _currentGrammarIndex;
	private ApiIndex _currentApi;
	private String _currentItem;

	public ItemDisambiguator(GlobalEnvironment env) {
		this._errors = new LinkedList<StaticError>();
		this._globalEnv = env;
	}

	private void error(String msg, HasAt loc) {
		this._errors.add(StaticError.make(msg, loc));
	}

	public Option<GrammarIndex> grammarIndex(final QualifiedIdName name) {
		if (name.getApi().isSome()) {
			APIName n = Option.unwrap(name.getApi());
			if (this._globalEnv.definesApi(n)) {
				return Option.some(_globalEnv.api(n).grammars().get(name));
			}
			else {
				return Option.none();
			}
		}
		return Option.some(((ApiIndex) _currentApi).grammars().get(name));
	}

	public static ApiResult disambiguateApis(Map<APIName, ApiIndex> map, GlobalEnvironment env) {
		Collection<ApiIndex> apis = new LinkedList<ApiIndex>();
		apis.addAll(map.values());
		apis.addAll(env.apis().values());
		initializeGrammarIndexExtensions(apis);
		
		List<Api> results = new ArrayList<Api>();
		ItemDisambiguator id = new ItemDisambiguator(env);
		for (ApiIndex api: apis) {
			Api idResult = (Api) api.ast().accept(id);
			if (id.errors().isEmpty()) {
				// Remove whitespace where instructed by non-whitespace symbols
				WhitespaceElimination we = new WhitespaceElimination();
				Api sdResult = (Api) idResult.accept(we);

				// Rewrite escaped characters
				EscapeRewriter escapeRewriter = new EscapeRewriter();
				Api erResult = (Api) sdResult.accept(escapeRewriter);
				results.add(erResult); 
				
			}
		}
		return new ApiResult(results, id.errors());
	}

	private static void initializeGrammarIndexExtensions(Collection<ApiIndex> apis) {
		Map<QualifiedIdName, GrammarIndex> grammars = new HashMap<QualifiedIdName, GrammarIndex>();
		for (ApiIndex a2: apis) {
			for (Entry<QualifiedIdName,GrammarIndex> e: a2.grammars().entrySet()) {
				grammars.put(e.getKey(), e.getValue());
			}
		}
		
		for (ApiIndex a1: apis) {
			for (Entry<QualifiedIdName,GrammarIndex> e: a1.grammars().entrySet()) {
				Option<GrammarDef> og = e.getValue().ast();
				if (og.isSome()) {
					List<GrammarIndex> ls = new LinkedList<GrammarIndex>();
					for (QualifiedIdName n: Option.unwrap(og).getExtends()) {
						ls.add(grammars.get(n));
					}
					e.getValue().setExtended(ls);
				}
			}
		}		
	}

	public Collection<StaticError> errors() {
		return this._errors;
	}

	@Override
	public Node forApi(Api that) {
		if (this._globalEnv.definesApi(that.getName())) {
			this._currentApi = this._globalEnv.api(that.getName());
		}
		else {
			error("Undefined api ", that);
		}
		return super.forApi(that);
	}

	@Override
	public Node forGrammarDef(GrammarDef that) {
		Option<GrammarIndex> index = this.grammarIndex(that.getName());
		if (index.isSome()) {
			this._currentGrammarIndex = Option.unwrap(index);
		}
		else {
			error("Grammar "+that.getName()+" not found", that); 
		}
		return super.forGrammarDef(that);
	}

	@Override
	public SyntaxSymbol forItemSymbol(ItemSymbol that) {
		SyntaxSymbol n = nameResolution(that);
		if (n == null) {
			error("Unknown item symbol: "+that, that);
		}
		if (n instanceof NonterminalSymbol ||
			n instanceof KeywordSymbol) {
			this._currentItem = that.getItem();
		}
		return n;
	}

	private SyntaxSymbol nameResolution(ItemSymbol item) {
		if (IdentifierUtil.validId(item.getItem())) {
			GrammarAnalyzer<GrammarIndex> ga = new GrammarAnalyzer<GrammarIndex>();
			QualifiedIdName name = makeQualifiedIdName(item.getSpan(), item.getItem());
			NonterminalNameDisambiguator nnd = new NonterminalNameDisambiguator(this._globalEnv);
			Option<QualifiedIdName> oname = nnd.handleProductionName(this._currentGrammarIndex.env(), name);
			
			if (oname.isSome()) {
				name = Option.unwrap(oname);
				
				Set<QualifiedIdName> setOfNonterminals = ga.getContained(name.getName(), this._currentGrammarIndex);

				if (setOfNonterminals.size() == 1) {
					this._errors.addAll(nnd.errors());
					return makeNonterminal(item, name);
				}

				if (setOfNonterminals.size() > 1) {
					this._errors.addAll(nnd.errors());
					error("Production name may refer to: " + NodeUtil.namesString(setOfNonterminals), name);
					return makeNonterminal(item, name);
				}
			}
			return makeKeywordSymbol(item);
		}
		return makeTokenSymbol(item);
	}

	private NonterminalSymbol makeNonterminal(ItemSymbol that, QualifiedIdName name) {
		return new NonterminalSymbol(that.getSpan(), name);
	}

	private KeywordSymbol makeKeywordSymbol(ItemSymbol item) {
		return new KeywordSymbol(item.getSpan(), item.getItem());
	}

	private TokenSymbol makeTokenSymbol(ItemSymbol item) {
		return new TokenSymbol(item.getSpan(), item.getItem());
	}

	private static QualifiedIdName makeQualifiedIdName(Span span, String item) {
		int lastIndexOf = item.lastIndexOf('.');
		if (lastIndexOf != -1) {
			APIName apiName = NodeFactory.makeAPIName(item.substring(0, lastIndexOf));
			return NodeFactory.makeQualifiedIdName(apiName, NodeFactory.makeId(item.substring(lastIndexOf+1)));
		}
		else {
			return NodeFactory.makeQualifiedIdName(span, item);
		}
	}
	
	@Override
	public Node forPrefixedSymbolOnly(final PrefixedSymbol prefix,
			final Option<Id> id_result, SyntaxSymbol symbol_result) {
		
		SyntaxSymbol s = symbol_result;
		Node n = s.accept(new NodeUpdateVisitor(){
			@Override
			public Node forItemSymbol(ItemSymbol that) {
				return handle(that, that.getItem());
			}

			@Override
			public Node forNonterminalSymbol(NonterminalSymbol that) {
				return handle(that, _currentItem);
			}
			
			@Override
			public Node forKeywordSymbol(KeywordSymbol that) {
				return handle(that, that.getToken());
			}

			@Override
			public Node forTokenSymbol(TokenSymbol that) {
				if (id_result.isNone()) {
					return that;
				}
				return handle(that, that.getToken());
			}

			private Node handle(SyntaxSymbol that, String s) {
				if (id_result.isNone()) {
					Id var = NodeFactory.makeId(s);
					return new PrefixedSymbol(prefix.getSpan(), Option.wrap(var),that);
				}
				else {
					return new PrefixedSymbol(prefix.getSpan(), id_result,that);
				}
			}
		});
		if (n instanceof SyntaxSymbol) {
			s = (SyntaxSymbol) n;
			s.getSpan().begin = prefix.getSpan().begin;
			s.getSpan().end = prefix.getSpan().end;
			return s;
		}
		throw new RuntimeException("Prefix symbol contained something different than a syntax symbol: "+ n.getClass().toString());
	}

}
