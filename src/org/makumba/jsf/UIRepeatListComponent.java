package org.makumba.jsf;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.util.Dictionary;
import java.util.List;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.el.ValueExpression;
import javax.faces.component.UIComponent;
import javax.faces.component.UIComponentBase;
import javax.faces.component.visit.VisitCallback;
import javax.faces.component.visit.VisitContext;
import javax.faces.component.visit.VisitResult;
import javax.faces.context.FacesContext;
import javax.faces.event.FacesEvent;
import javax.faces.event.PhaseId;
import javax.faces.model.DataModel;
import javax.faces.model.ListDataModel;
import javax.faces.view.facelets.FaceletException;

import org.makumba.ProgrammerError;
import org.makumba.commons.ArrayMap;
import org.makumba.commons.NamedResourceFactory;
import org.makumba.commons.NamedResources;
import org.makumba.commons.RegExpUtils;
import org.makumba.list.engine.ComposedQuery;
import org.makumba.list.engine.ComposedSubquery;
import org.makumba.list.engine.Grouper;
import org.makumba.providers.QueryProvider;
import org.makumba.providers.TransactionProvider;

import com.sun.faces.facelets.compiler.UIInstructions;
import com.sun.faces.facelets.component.UIRepeat;

public class UIRepeatListComponent extends UIRepeat {

    /*
     * Since UIInstructions does not update UIComponent.CURRENT_COMPONENT, we wrap it in a normal component
     */
    public static final class UIInstructionWrapper extends UIComponentBase {
        private final UIComponent kid;

        private UIInstructionWrapper(UIComponent kid) {
            this.kid = kid;
            kid.setParent(null);
            getChildren().add(kid);
        }

        @Override
        public String getFamily() {
            return kid.getFamily();
        }

        /*
         * this most probably returns true 
         */
        @Override
        public boolean isTransient() {
            return kid.isTransient();
        }
    }

    static final String CURRENT_DATA = "org.makumba.list.currentData";

    static final private Dictionary<String, Object> NOTHING = new ArrayMap();

    String[] queryProps = new String[6];

    String separator = "";

    // TODO: no clue what defaultLimit does
    int offset = 0, limit = -1, defaultLimit;

    transient ComposedQuery composedQuery;

    // all data, from all iterations of the parent list
    transient Grouper listData;

    // current iteration of this list
    transient ArrayMap currentData;

    private String prefix;

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    // FLAGS, should be taken from configuration
    /**
     * We can execute the queries of a mak:list group either in the same transaction or separately. In JSP they are
     * executed separately and no major issues were found. In JSF we test executing them together but we provide this
     * flag.
     */
    public boolean useSeparateTransactions() {
        return false;
    }

    /**
     * Should be true in production and false in development. Tells whether we should recompute the queries at every
     * load. It may be possible to detect automatically whether the view script has changed. If it changes only a bit,
     * the keys don't change much.
     */
    public boolean useCaches() {
        return false;
    }

    // END OF FLAGS

    public ComposedQuery getComposedQuery() {
        return composedQuery;
    }

    public List<String> getProjections() {
        return getComposedQuery().getProjections();
    }

    public void setFrom(String s) {
        queryProps[ComposedQuery.FROM] = s;
    }

    protected Object getCacheKey() {
        // TODO: find an implementation-independent cache key
        return this.getAttributes().get("com.sun.faces.facelets.MARK_ID");
    }

    public void setVariableFrom(String s) {
        queryProps[ComposedQuery.VARFROM] = s;
    }

    public void setWhere(String s) {
        queryProps[ComposedQuery.WHERE] = s;
    }

    public void setOrderBy(String s) {
        queryProps[ComposedQuery.ORDERBY] = s;
    }

    public void setGroupBy(String s) {
        queryProps[ComposedQuery.GROUPBY] = s;
    }

    public void setSeparator(String s) {
        separator = s;
    }

    public void setOffset(int n) {
        offset = n;
    }

    public void setLimit(int n) {
        limit = n;
    }

    public void setDefaultLimit(int n) {
        defaultLimit = n;
    }

    protected void onlyOuterListArgument(String s) {
        UIRepeatListComponent c = UIRepeatListComponent.findMakListParent(this, false);
        if (c != null) {
            throw new FaceletException(s + "can be indicated only for root mak:lists");
        }
    }

    static UIRepeatListComponent findMakListParent(UIComponent current, boolean objectToo) {
        UIComponent c = current.getParent();
        while (c != null && !(c instanceof UIRepeatListComponent)) {
            // TODO: honor also objectToo
            c = c.getParent();
        }

        return (UIRepeatListComponent) c;
    }

    private boolean beforeIteration(PhaseId phaseId) {
        if (findMakListParent(this, true) == null) {
            startMakListGroup(phaseId);
        }
        // TODO: check whether we really want to keep the data in the grouper after iteration
        // this is only useful before a postback which will not request this list to re-render

        final List<ArrayMap> iterationGroupData = listData != null ? listData.getData(getCurrentDataStack(), false)
                : null;

        if (iterationGroupData == null) {
            return false;
        }

        // push a placeholder, it will be popped at first iteration
        getCurrentDataStack().push(NOTHING);

        DataModel<ArrayMap> dm = new ListDataModel<ArrayMap>(iterationGroupData) {
            @Override
            public void setRowIndex(int rowIndex) {
                if (rowIndex >= 0 && rowIndex < iterationGroupData.size()) {
                    // pop old value:
                    getCurrentDataStack().pop();
                    currentData = iterationGroupData.get(rowIndex);
                    // push new value:
                    getCurrentDataStack().push(currentData);
                }

                super.setRowIndex(rowIndex);
                if (rowIndex >= iterationGroupData.size()) {
                    // nothing but we could use this to replace afterIteration()
                }

            }
        };

        setValue(dm);
        setBegin(0);
        setEnd(iterationGroupData.size());

        return true;
    }

    private void afterIteration() {
        // this list is done, no more current value in stack
        getCurrentDataStack().pop();
    }

    @Override
    public void queueEvent(FacesEvent event) {
        /* 
         * here we can detect Ajax and ValueChanged events, but they are always sent to the root mak:list
        no matter which mak:list is the target of the f:ajax render= 
         */
        System.out.println(event + " " + composedQuery);
        super.queueEvent(event);
    }

    @Override
    public void process(FacesContext context, PhaseId p) {

        System.out.println(p + " " + composedQuery);
        if (!beforeIteration(p)) {
            return;
        }
        try {
            super.process(context, p);
        } finally {
            afterIteration();
        }
    }

    @Override
    public boolean visitTree(final VisitContext context, final VisitCallback callback) {

        if (callback.getClass().getName().indexOf("PartialViewContext") != -1) {

            /* 
             * we may be able to figure out that this is a partial rendering and execute queries only 
             * on new renderings and on partial renderings
             * 
             * Clearly the last rendering after an ajax call is done 
             * only on the mak:list that was indicated in f:ajax render=
             */
            System.out.println("PartialViewContext " + composedQuery);
        }
        if (listData == null) {
            // there is no data, hopefully we are in the process of restoring it
            // so we call the visiting only on this component
            context.invokeVisitCallback(this, callback);
            // since we had no data we probably had no structure either, so now we can wrap strage things
            if (findMakListParent(this, true) == null) {
                wrapUIInstrutions();
            }
        }

        // if there's no data, we should not iterate
        if (!beforeIteration(null)) {
            return false;
        }

        try {
            return super.visitTree(context, new VisitCallback() {
                @Override
                public VisitResult visit(VisitContext c, UIComponent target) {
                    // System.out.println(target.getClass());
                    return callback.visit(context, target);
                }

            });
        } finally {
            afterIteration();
        }
    }

    @SuppressWarnings("unchecked")
    static Stack<Dictionary<String, Object>> getCurrentDataStack() {
        return (Stack<Dictionary<String, Object>>) FacesContext.getCurrentInstance().getExternalContext().getRequestMap().get(
            CURRENT_DATA);
    }

    public static UIRepeatListComponent getCurrentlyRunning() {
        return findMakListParent((UIComponent) FacesContext.getCurrentInstance().getAttributes().get(
            UIComponent.CURRENT_COMPONENT), true);
    }

    static int composedQueries = NamedResources.makeStaticCache("JSF ComposedQueries", new NamedResourceFactory() {
        @Override
        public Object getHashObject(Object o) {
            return ((UIRepeatListComponent) o).getCacheKey();
        }

        @Override
        public Object makeResource(Object o, Object hashName) throws Throwable {
            UIRepeatListComponent comp = (UIRepeatListComponent) o;
            comp.computeComposedQuery();
            return comp.composedQuery;
        }
    });

    public void analyze() {
        // this method is called only for root mak:lists, thus it would be good for triggering analysis and executing
        // queries
        // however for some reason it is called twice if APPLY_REQUEST_VALUES 2 PROCESS_VALIDATIONS 3 and
        // UPDATE_MODEL_VALUES 4 are executed.
        // thus analysis is now done in encodeAll() (i.e. at the latest possible moment)
        // TODO: consider removing
    }

    private void wrapUIInstrutions() {
        visitStaticTree(this, new VisitCallback() {
            @Override
            public VisitResult visit(VisitContext context, UIComponent target) {
                if (target instanceof UIInstructionWrapper) {
                    return VisitResult.REJECT;
                }

                for (int i = 0; i < target.getChildren().size(); i++) {
                    final UIComponent kid = target.getChildren().get(i);
                    if (kid instanceof UIInstructions) {
                        // System.out.println("\t" + kid.getClass());
                        target.getChildren().set(i, new UIInstructionWrapper(kid));
                    }
                }
                return VisitResult.ACCEPT;
            }
        });
    }

    static void visitStaticTree(UIComponent target, VisitCallback c) {
        if (c.visit(null, target) == VisitResult.REJECT) {
            return;
        }
        for (UIComponent kid : target.getChildren()) {
            visitStaticTree(kid, c);
        }
    }

    public void startMakListGroup(final PhaseId phaseId) {

        readComposedQuery();

        final QueryProvider qep = useSeparateTransactions() ? null : getQueryExecutionProvider();

        try {

            // we only execute queries during RENDER_RESPONSE
            // we might even skip that if we have data (listData!=null)
            if (phaseId == PhaseId.RENDER_RESPONSE) {
                if (listData == null) {
                    // if we had no data, we probably had no structure, so we explore it now
                    wrapUIInstrutions();
                }
                executeGroupQueries(qep);
            }

        } finally {
            if (qep != null) {
                qep.close();
            }
        }
        // we are in root, we initialize the data stack
        FacesContext.getCurrentInstance().getExternalContext().getRequestMap().put(CURRENT_DATA,
            new Stack<Dictionary<String, Object>>());

        // and we push the key needed for the root mak:list to find its data (see beforeIteration)
        getCurrentDataStack().push(NOTHING);

    }

    private void executeGroupQueries(final QueryProvider qep) {
        visitStaticTree(this, new VisitCallback() {
            @Override
            public VisitResult visit(VisitContext context, UIComponent target) {
                if (target instanceof UIRepeatListComponent) {
                    ((UIRepeatListComponent) target).executeQuery(qep);
                }
                return VisitResult.ACCEPT;
            }
        });
    }

    private void readComposedQuery() {
        if (composedQuery == null) {
            if (useCaches()) {
                composedQuery = (ComposedQuery) NamedResources.getStaticCache(composedQueries).getResource(this);
            } else {
                computeComposedQuery();
            }
        }
    }

    void computeComposedQuery() {
        UIRepeatListComponent parent = findMakListParent(this, true);
        if (parent == null) {
            // no parent, we are root
            this.composedQuery = new ComposedQuery(this.queryProps, this.getQueryLanguage());
        } else {
            this.composedQuery = new ComposedSubquery(this.queryProps, parent.composedQuery, this.getQueryLanguage());
        }
        this.composedQuery.init();
        this.findExpressionsInChildren();
        if (parent == null) {
            this.analyzeMakListGroup();
        }
        this.composedQuery.analyze();
        // System.out.println(this.composedQuery);
    }

    void analyzeMakListGroup() {
        visitStaticTree(this, new VisitCallback() {
            @Override
            public VisitResult visit(VisitContext context, UIComponent target) {
                if (target != UIRepeatListComponent.this && target instanceof UIRepeatListComponent) {
                    ((UIRepeatListComponent) target).readComposedQuery();
                }
                return VisitResult.ACCEPT;
            }
        });
    }

    static final ComposedQuery.Evaluator evaluator = new ComposedQuery.Evaluator() {
        @Override
        public String evaluate(String s) {
            FacesContext ctx = FacesContext.getCurrentInstance();
            // FIXME: no clue if this is what we should do here
            return ctx.getApplication().evaluateExpressionGet(ctx, s, String.class);
        }
    };

    private void executeQuery(QueryProvider qep) {
        // by now the query was cached so we fetch it
        readComposedQuery();
        if (useSeparateTransactions()) {
            qep = getQueryExecutionProvider();
        }

        try {
            System.out.println("Executing " + composedQuery);
            listData = composedQuery.execute(qep, null, evaluator, offset, limit);
        } finally {
            if (useSeparateTransactions()) {
                qep.close();
            }
        }
    }

    private QueryProvider getQueryExecutionProvider() {
        return QueryProvider.makeQueryRunner(TransactionProvider.getInstance().getDefaultDataSourceName(),
            getQueryLanguage());
    }

    public String getQueryLanguage() {
        // TODO: get the query language from taglib URI, taglib name, or configuration
        return "oql";
    }

    private void addExpression(String expr, boolean canBeInvalid) {
        // TODO: analyze the expression in the composedquery. mak:value and mak:expr() expressions may not be invalid,
        // while other EL expressions may be invalid, in which case they are not added
        composedQuery.checkProjectionInteger(expr);
    }

    Integer getExpressionIndex(String expr) {
        Integer exprIndex = composedQuery.checkProjectionInteger(expr);
        if (exprIndex == null) {
            if (useCaches()) {
                // FIXME: a better mak:list description
                throw new ProgrammerError("<mak:list> does not know the expression " + expr
                        + ", turn caches off, or try reloading the page, it might work.");
            } else {
                // second call should return not null
                // however, we should never get here since a page analysis is done every request
                // so the expression must be known
                exprIndex = composedQuery.checkProjectionInteger(expr);
            }
        }
        return exprIndex;
    }

    public Object getExpressionValue(String expr) {
        return getExpressionValue(getExpressionIndex(expr));
    }

    public Object getExpressionValue(int exprIndex) {
        return currentData.data[exprIndex];
    }

    void findExpressionsInChildren() {
        visitStaticTree(this, new VisitCallback() {
            @Override
            public VisitResult visit(VisitContext context, UIComponent target) {
                if (target instanceof UIRepeatListComponent && target != UIRepeatListComponent.this) {
                    return VisitResult.REJECT;
                }

                // System.out.println(target);

                if (target instanceof UIInstructions) {
                    findFloatingExpressions((UIInstructions) target);
                } else if (target instanceof ValueComponent) {
                    findMakValueExpressions((ValueComponent) target);
                } else {
                    findComponentExpressions(target);
                }
                return VisitResult.ACCEPT;
            }
        });
    }

    /**
     * Finds the properties of this {@link UIComponent} that have a {@link ValueExpression}.<br>
     * TODO: we can't really cache this since the programmer can change the view without restarting the whole servlet
     * context. We may be able to find out about a change in the view though and introduce a caching mechanism then.
     * 
     * @param component
     *            the {@link UIComponent} of which the properties should be searched for EL expressions
     */
    private void findComponentExpressions(UIComponent component) {

        try {
            PropertyDescriptor[] pd = Introspector.getBeanInfo(component.getClass()).getPropertyDescriptors();
            for (PropertyDescriptor p : pd) {
                // we try to see if this is a ValueExpression by probing it
                ValueExpression ve = component.getValueExpression(p.getName());
                if (ve != null) {
                    addExpression(trimExpression(ve.getExpressionString()), true);
                }
            }

        } catch (IntrospectionException e) {
            // TODO better error handling
            e.printStackTrace();
        }
    }

    private final static Pattern ELExprFunctionPattern = Pattern.compile("\\w+:expr\\(" + RegExpUtils.LineWhitespaces
            + "(\\'[^\\']+\\')" + RegExpUtils.LineWhitespaces + "?\\)");

    private final static Pattern JSFELPattern = Pattern.compile("\\#\\{[^\\}]*\\}");

    private final static Pattern dotPathPattern = Pattern.compile(RegExpUtils.dotPath);

    /**
     * Finds the 'floating' EL expressions that are not a property of a component, but are directly part of the view
     * body. Since {@link UIInstructions} sometimes not only return the EL expression but also some surrounding HTML
     * tags, we do a rudimentary but robust parsing (everything that does not conform to <code>#{...}</code> is
     * ignored).<br>
     * FIXME this is a hack and renders this implementation dependent on the Sun Mojarra implementation. That is, the
     * JSF specification does not seem to say anything about such floating EL elements. There might be a way to get
     * those through the ELResolver facility though.
     * 
     * @param component
     *            the {@link UIInstructions} which should be searched for EL expressions.
     */
    private void findFloatingExpressions(UIInstructions component) {

        String txt = component.toString();

        // find EL expressions
        Matcher elExprMatcher = JSFELPattern.matcher(txt);
        while (elExprMatcher.find()) {
            String elExprTxt = elExprMatcher.group();
            elExprTxt.substring(2, elExprTxt.length() - 1);

            // first we find functions inside of it
            Matcher exprFuncMatcher = ELExprFunctionPattern.matcher(elExprTxt);

            while (exprFuncMatcher.find()) {
                String elFuncTxt = exprFuncMatcher.group();

                if (elFuncTxt.startsWith(prefix)) {
                    elFuncTxt = elFuncTxt.substring(prefix.length() + ":expr(".length(), elFuncTxt.length() - 1);

                    // TODO: decide whether we want to support dynamic function expressions
                    // if not, check that txt is precisely a 'string' or "string"
                    // to support dynamic function expressions, an evaluator should be applied here
                    elFuncTxt = elFuncTxt.substring(1, elFuncTxt.length() - 1);
                    addExpression(elFuncTxt, false);
                } else {
                    // TODO logger warning or namespace resolution
                }
            }
            // remove the EL function calls from the global expression to avoid wrong matches of the rest
            elExprTxt = exprFuncMatcher.replaceAll("");

            // we now have a cleared expression, we check for paths like "p.name"
            Matcher dotPathMatcher = dotPathPattern.matcher(elExprTxt);
            while (dotPathMatcher.find()) {
                addExpression(dotPathMatcher.group(), true);
            }
        }
    }

    /**
     * Finds QL expressions inside a mak:value component
     * 
     * @param component
     *            the mak:value component
     */
    private void findMakValueExpressions(ValueComponent component) {

        // go thru all properties as for normal components, and also take into account non-EL (literal) expr values
        findComponentExpressions(component);
        if (component.getExpr() == null) {
            // FIXME ProgrammerError
            throw new RuntimeException("no expr provided in mak:value!");
        } else {
            // TODO: setvalue expression
            // TODO: nullable value? i guess that's not in use any longer
            addExpression(component.getExpr(), false);
        }

    }

    static private String trimExpression(String expr) {
        return expr.substring(2, expr.length() - 1);
    }

    @Override
    public void restoreState(FacesContext faces, Object object) {
        if (faces == null) {
            throw new NullPointerException();
        }
        if (object == null) {
            return;
        }
        Object[] state = (Object[]) object;
        super.restoreState(faces, state[0]);
        // noinspection unchecked
        this.listData = (Grouper) state[1];
        this.composedQuery = (ComposedQuery) state[2];
    }

    @Override
    public Object saveState(FacesContext faces) {
        if (faces == null) {
            throw new NullPointerException();
        }

        Object[] state = new Object[8];
        Object o = getValue();

        // we avoid serialization of the value which is a ListDataModel, non-serializable and not needed anyway
        setValue(null);
        state[0] = super.saveState(faces);
        setValue(o);

        state[1] = listData;
        state[2] = composedQuery;
        // TODO: save other needed stuff
        return state;
    }

}
