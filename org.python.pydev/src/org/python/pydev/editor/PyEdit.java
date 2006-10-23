package org.python.pydev.editor;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.ListResourceBundle;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Preferences;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.Assert;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IEditorActionBarContributor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IStorageEditorInput;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.editors.text.ILocationProvider;
import org.eclipse.ui.editors.text.TextFileDocumentProvider;
import org.eclipse.ui.internal.util.SWTResourceUtil;
import org.eclipse.ui.part.EditorActionBarContributor;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.AbstractDecoratedTextEditorPreferenceConstants;
import org.eclipse.ui.texteditor.DefaultRangeIndicator;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.IEditorStatusLine;
import org.eclipse.ui.texteditor.MarkerUtilities;
import org.eclipse.ui.texteditor.TextOperationAction;
import org.eclipse.ui.views.contentoutline.IContentOutlinePage;
import org.python.copiedfromeclipsesrc.PydevFileEditorInput;
import org.python.pydev.builder.PyDevBuilderPrefPage;
import org.python.pydev.core.ExtensionHelper;
import org.python.pydev.core.IPyEdit;
import org.python.pydev.core.IPythonNature;
import org.python.pydev.core.REF;
import org.python.pydev.core.Tuple;
import org.python.pydev.core.docutils.PyPartitionScanner;
import org.python.pydev.core.docutils.PySelection;
import org.python.pydev.editor.actions.OfflineAction;
import org.python.pydev.editor.actions.OfflineActionTarget;
import org.python.pydev.editor.actions.PyAction;
import org.python.pydev.editor.actions.PyOpenAction;
import org.python.pydev.editor.autoedit.IIndentPrefs;
import org.python.pydev.editor.autoedit.PyAutoIndentStrategy;
import org.python.pydev.editor.codecompletion.shell.AbstractShell;
import org.python.pydev.editor.codefolding.CodeFoldingSetter;
import org.python.pydev.editor.codefolding.PyEditProjection;
import org.python.pydev.editor.model.IModelListener;
import org.python.pydev.editor.scripting.PyEditScripting;
import org.python.pydev.outline.PyOutlinePage;
import org.python.pydev.parser.PyParser;
import org.python.pydev.parser.jython.ParseException;
import org.python.pydev.parser.jython.SimpleNode;
import org.python.pydev.parser.jython.Token;
import org.python.pydev.parser.jython.TokenMgrError;
import org.python.pydev.parser.visitors.NodeUtils;
import org.python.pydev.parser.visitors.scope.ASTEntry;
import org.python.pydev.plugin.PydevPlugin;
import org.python.pydev.plugin.PydevPrefs;
import org.python.pydev.plugin.nature.PythonNature;
import org.python.pydev.plugin.nature.SystemPythonNature;
import org.python.pydev.ui.ColorCache;
import org.python.pydev.ui.NotConfiguredInterpreterException;

/**
 * The TextWidget.
 * 
 * <p>
 * Ties together all the main classes in this plugin.
 * <li>The {@link org.python.pydev.editor.PyEditConfiguration PyEditConfiguration}does preliminary partitioning.
 * <li>The {@link org.python.pydev.parser.PyParser PyParser}does a lazy validating python parse.
 * <li>The {@link org.python.pydev.outline.PyOutlinePage PyOutlinePage}shows the outline
 * 
 * <p>
 * Listens to the parser's events, and displays error markers from the parser.
 * 
 * <p>
 * General notes:
 * <p>
 * TextWidget creates SourceViewer, an SWT control
 * 
 * @see <a href="http://dev.eclipse.org/newslists/news.eclipse.tools/msg61594.html">This eclipse article was an inspiration </a>
 *  
 */
public class PyEdit extends PyEditProjection implements IPyEdit {

    public static final String PY_EDIT_CONTEXT = "#PyEditContext";

    static public String EDITOR_ID = "org.python.pydev.editor.PythonEditor";

    static public String ACTION_OPEN = "OpenEditor";

    /** color cache */
    private ColorCache colorCache;

    /** lexical parser that continuously reparses the document on a thread */
    private PyParser parser;

    // Listener waits for tab/spaces preferences that affect sourceViewer
    private Preferences.IPropertyChangeListener prefListener;

    /** need it to support GUESS_TAB_SUBSTITUTION preference */
    private PyAutoIndentStrategy indentStrategy;

    /** need to hold onto it to support indentPrefix change through preferences */
    PyEditConfiguration editConfiguration;

    /**
     * AST that created python model
     */
    SimpleNode ast;

    /** Hyperlinking listener */
    Hyperlink fMouseListener;

    /** listeners that get notified of model changes */
    List<IModelListener> modelListeners;

    // ---------------------------- listeners stuff
    /**
     * Those are the ones that register with the PYDEV_PYEDIT_LISTENER extension point
     */
    private static List<IPyEditListener> editListeners;
    
    /**
     * Those are the ones that register at runtime (not throught extensions points).
     */
    private List<IPyEditListener> registeredEditListeners = new ArrayList<IPyEditListener>();

    /**
     * This is the scripting engine that is binded to this interpreter.
     */
	private PyEditScripting pyEditScripting;

	/**
	 * Lock for initialization sync
	 */
	private Object lock = new Object();
    
    public void addPyeditListener(IPyEditListener listener){
        registeredEditListeners.add(listener);
    }
    
    public void removePyeditListener(IPyEditListener listener){
        registeredEditListeners.remove(listener);
    }

    

    @Override
    protected void handleCursorPositionChanged() {
        super.handleCursorPositionChanged();
        if(!initFinished){
        	return;
        }
        for(IPyEditListener listener : getAllListeners()){
            try {
                if(listener instanceof IPyEditListener2){
                    ((IPyEditListener2)listener).handleCursorPositionChanged(this);
                }
            } catch (Throwable e) {
                //must not fail
                PydevPlugin.log(e);
            }
        }
    }

    public List<IPyEditListener> getAllListeners() {
    	while (initFinished == false){
    		synchronized(getLock()){
    			try {
    				getLock().wait();
				} catch (Exception e) {
					//ignore
					e.printStackTrace();
				}
    		}
    	}
        ArrayList<IPyEditListener> listeners = new ArrayList<IPyEditListener>();
        if(editListeners != null){
            listeners.addAll(editListeners);
        }
        listeners.addAll(registeredEditListeners);
        return listeners;
    }

    private Object getLock() {
		return lock;
	}

	/**
     * This map may be used by clients to store info regarding this editor.
     * 
     * Clients should be careful so that this key is unique and does not conflict with other
     * plugins. 
     * 
     * This is not enforced.
     * 
     * The suggestion is that the cache key is always preceded by the class name that will use it.
     */
    public Map<String,Object> cache = new HashMap<String, Object>();

    /**
     * Indicates whether the init was already finished
     */
	protected boolean initFinished = false;

	private PyEditNotifier notifier;

	private boolean disposed = false;
	public boolean isDisposed() {
		return disposed;
	}
    
    // ---------------------------- end listeners stuff
    
    @SuppressWarnings("unchecked")
	public PyEdit() {
        super();
        
        //initialize the 'save' listeners of PyEdit
        if (editListeners == null){
        	editListeners = ExtensionHelper.getParticipants(ExtensionHelper.PYDEV_PYEDIT_LISTENER);
        }
        
        modelListeners = new ArrayList<IModelListener>();
        colorCache = new ColorCache(PydevPlugin.getChainedPrefStore());
        
        editConfiguration = new PyEditConfiguration(colorCache, this);
        setSourceViewerConfiguration(editConfiguration);
        indentStrategy = editConfiguration.getPyAutoIndentStrategy();
        setRangeIndicator(new DefaultRangeIndicator()); // enables standard
        // vertical ruler

        //Added to set the code folding.
        CodeFoldingSetter codeFoldingSetter = new CodeFoldingSetter(this);
        this.addModelListener(codeFoldingSetter);
        this.addPropertyListener(codeFoldingSetter);


    }

    
    /**
     * Sets the forceTabs preference for auto-indentation.
     * 
     * <p>
     * This is the preference that overrides "use spaces" preference when file contains tabs (like mine do).
     * <p>
     * If the first indented line starts with a tab, then tabs override spaces.
     */
    private void resetForceTabs() {
        IDocument doc = getDocumentProvider().getDocument(getEditorInput());
        if (doc == null){
            return;
        }
        
        if (!PydevPrefs.getPreferences().getBoolean(PydevPrefs.GUESS_TAB_SUBSTITUTION)) {
            getIndentPrefs().setForceTabs(false);
            return;
        }

        int lines = doc.getNumberOfLines();
        boolean forceTabs = false;
        int i = 0;
        // look for the first line that starts with ' ', or '\t'
        while (i < lines) {
            try {
                IRegion r = doc.getLineInformation(i);
                String text = doc.get(r.getOffset(), r.getLength());
                if (text != null)
                    if (text.startsWith("\t")) {
                        forceTabs = true;
                        break;
                    } else if (text.startsWith("  ")) {
                        forceTabs = false;
                        break;
                    }
            } catch (BadLocationException e) {
                PydevPlugin.log(IStatus.ERROR, "Unexpected error forcing tabs", e);
                break;
            }
            i++;
        }
        getIndentPrefs().setForceTabs(forceTabs);
        editConfiguration.resetIndentPrefixes();
        // display a message in the status line
        if (forceTabs) {
            IEditorStatusLine statusLine = (IEditorStatusLine) getAdapter(IEditorStatusLine.class);
            if (statusLine != null)
                statusLine.setMessage(false, "Pydev: forcing tabs", null);
        }
    }

    /**
     * @return the indentation preferences
     */
    public IIndentPrefs getIndentPrefs() {
        return indentStrategy.getIndentPrefs();
    }

    /**
     * Initializes everyone that needs document access
     *  
     */
    public void init(final IEditorSite site, final IEditorInput input) throws PartInitException {
    	notifier = new PyEditNotifier(this);
        super.init(site, input);

        final IDocument document = getDocument(input);
        
        // check the document partitioner (sanity check / fix)
        PyPartitionScanner.checkPartitionScanner(document);

        checkAndCreateParserIfNeeded();


        // Also adds Python nature to the project.
    	// The reason this is done here is because I want to assign python
    	// nature automatically to any project that has active python files.
        final IPythonNature nature = PythonNature.addNature(input);
        
        //we also want to initialize our shells...
        //we use 2: one for refactoring and one for code completion.
        Thread thread2 = new Thread() {
            public void run() {
                try {
                    //ok, behaviour change... we just initialize the shell that is not for code completion
                    //when requested...
//                    try {
//                        PythonShell.getServerShell(PythonShell.OTHERS_SHELL);
//                    } catch (RuntimeException e1) {
//                    }
                    try {
                        AbstractShell.getServerShell(nature, AbstractShell.COMPLETION_SHELL);
                    } catch (RuntimeException e1) {
                    }
                } catch (Exception e) {
                }

            }
        };
        thread2.setName("Shell starter");
        thread2.start();

        
        // listen to changes in TAB_WIDTH preference
        prefListener = new Preferences.IPropertyChangeListener() {
            public void propertyChange(Preferences.PropertyChangeEvent event) {
                String property = event.getProperty();
                //tab width
                if (property.equals(PydevPrefs.TAB_WIDTH)) {
                    ISourceViewer sourceViewer = getSourceViewer();
                    if (sourceViewer == null){
                        return;
                    }
                    getIndentPrefs().regenerateIndentString();
                    sourceViewer.getTextWidget().setTabs(PydevPlugin.getDefault().getPluginPreferences().getInt(PydevPrefs.TAB_WIDTH));
                    
                }else if (property.equals(PydevPrefs.SUBSTITUTE_TABS)) {
                	getIndentPrefs().regenerateIndentString();
                   
                //auto adjust for file tabs
                } else if (property.equals(PydevPrefs.GUESS_TAB_SUBSTITUTION)) {
                    resetForceTabs();
                    
                //hyperlink
                }else if (property.equals(AbstractDecoratedTextEditorPreferenceConstants.EDITOR_HYPERLINK_COLOR)) {
                    colorCache.reloadNamedColor(property);
                    if (fMouseListener != null){
                        fMouseListener.updateColor(getSourceViewer());
                    }
                
                //colors and styles
                } else if (property.equals(PydevPrefs.CODE_COLOR) || property.equals(PydevPrefs.DECORATOR_COLOR) || property.equals(PydevPrefs.NUMBER_COLOR)
                        || property.equals(PydevPrefs.KEYWORD_COLOR) || property.equals(PydevPrefs.SELF_COLOR) || property.equals(PydevPrefs.COMMENT_COLOR) 
                        || property.equals(PydevPrefs.STRING_COLOR) || property.equals(PydevPrefs.CLASS_NAME_COLOR) || property.equals(PydevPrefs.FUNC_NAME_COLOR)
                        || property.equals(PydevPrefs.DEFAULT_BACKQUOTES_COLOR)
                        || property.endsWith("_STYLE")
                        ) {
                    colorCache.reloadNamedColor(property); //all reference this cache
                    editConfiguration.updateSyntaxColorAndStyle(); //the style needs no reloading
                    getSourceViewer().invalidateTextPresentation();
                    
                } else if(property.equals(PyDevBuilderPrefPage.USE_PYDEV_ANALYSIS_ONLY_ON_DOC_SAVE) || property.equals(PyDevBuilderPrefPage.PYDEV_ELAPSE_BEFORE_ANALYSIS)){
                    parser.reset(PyDevBuilderPrefPage.useAnalysisOnlyOnDocSave(), PyDevBuilderPrefPage.getElapseMillisBeforeAnalysis());
                }
            }
        };
        resetForceTabs();
        PydevPrefs.getPreferences().addPropertyChangeListener(prefListener);
        
    
        Runnable runnable = new Runnable(){

			public void run() {
				//let's do that in a thread, so that we don't have any delays in setting up the editor
				pyEditScripting = new PyEditScripting();
				addPyeditListener(pyEditScripting);
				
				//set the parser for the document
				parser.setDocument(document);
				notifier.notifyOnSetDocument(document);
				initFinished = true;
				synchronized(getLock()){
					getLock().notifyAll();
				}
			}
        };
        Thread thread = new Thread(runnable);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.setName("PyEdit initializer");
        thread.start();

    }

    /**
     * Checks if the parser exists. If it doesn't, creates it.
     */
    private void checkAndCreateParserIfNeeded() {
        if(parser == null){
            parser = new PyParser(this);
            parser.addParseListener(this);
        }
    }
    

    /**
     * When we have the editor input re-set, we have to change the parser and the partition scanner to
     * the new document. (this happens in 2 cases: 
     * - when the editor is reused in the search window
     * - when we create a file, and make a save as, to change its name
     * 
     * there were related bugs in each of these cases:
     * https://sourceforge.net/tracker/?func=detail&atid=577329&aid=1250307&group_id=85796
     * https://sourceforge.net/tracker/?func=detail&atid=577329&aid=1251271&group_id=85796
     *  
     * @see org.eclipse.ui.texteditor.AbstractTextEditor#doSetInput(org.eclipse.ui.IEditorInput)
     */
    @Override
    protected void doSetInput(IEditorInput input) throws CoreException {
        super.doSetInput(input);
        IDocument document = getDocument(input);
        //see if we have to change the encoding of the file on load
        fixEncoding(input, document);

        checkAndCreateParserIfNeeded();
        if(document != null){
            parser.setDocument(document);
            PyPartitionScanner.checkPartitionScanner(document);
        }
        notifier.notifyOnSetDocument(document);
    }
    
    /**
     * @param input
     * @return
     */
    private IDocument getDocument(final IEditorInput input) {
        return getDocumentProvider().getDocument(input);
    }

    /**
     * @return the document that is binded to this editor (may be null)
     */
    public IDocument getDocument() {
        IDocumentProvider documentProvider = getDocumentProvider();
        if(documentProvider != null){
            return documentProvider.getDocument(getEditorInput());
        }
        return null;
    }
    
    /** 
     * @see org.eclipse.ui.texteditor.AbstractTextEditor#performSave(boolean, org.eclipse.core.runtime.IProgressMonitor)
     */
    protected void performSave(boolean overwrite, IProgressMonitor progressMonitor) {
        fixEncoding(getEditorInput(), getDocument());
        super.performSave(overwrite, progressMonitor);
        parser.notifySaved();
        notifier.notifyOnSave();
    }

    /**
     * Forces the encoding to the one specified in the file
     * 
     * @param input
     * @param document
     */
    private void fixEncoding(final IEditorInput input, IDocument document) {
        if (input instanceof FileEditorInput) {
            final IFile file = (IFile) ((FileEditorInput) input).getAdapter(IFile.class);
            final String encoding = REF.getPythonFileEncoding(document, file.getFullPath().toOSString());
            if (encoding != null) {
                try {
                    if (encoding.equals(file.getCharset()) == false) {

                        new Job("Change encoding") {

                            protected IStatus run(IProgressMonitor monitor) {
                                System.out.println("Changing Encoding");
                                try {
                                    file.setCharset(encoding, monitor);
                                    ((TextFileDocumentProvider) getDocumentProvider()).setEncoding(input, encoding);
                                    //refresh it...
                                    file.refreshLocal(IResource.DEPTH_INFINITE, null);
                                } catch (CoreException e) {
                                    PydevPlugin.log(e);
                                }
                                return Status.OK_STATUS;
                            }

                        }.schedule();
                    }
                } catch (CoreException e) {
                    PydevPlugin.log(e);
                }
            }
        }
    }

    public IProject getProject() {
        IEditorInput editorInput = this.getEditorInput();
        if (editorInput instanceof FileEditorInput) {
            IFile file = (IFile) ((FileEditorInput) editorInput).getAdapter(IFile.class);
            return file.getProject();
        }
        return null;
    }
    

    public IFile getIFile() {
        IEditorInput editorInput = this.getEditorInput();
        if (editorInput instanceof FileEditorInput) {
            IFile file = (IFile) ((FileEditorInput) editorInput).getAdapter(IFile.class);
            return file;
        }
        return null;
        
    }
    
    /**
     * @return
     *  
     */
    public File getEditorFile() {
        File f = null;
        IEditorInput editorInput = this.getEditorInput();
        if (editorInput instanceof FileEditorInput) {
            IFile file = (IFile) ((FileEditorInput) editorInput).getAdapter(IFile.class);

            IPath path = file.getLocation().makeAbsolute();
            f = path.toFile();
        
        }else if (editorInput instanceof PydevFileEditorInput) {
        	PydevFileEditorInput pyEditorInput = (PydevFileEditorInput) editorInput;
        	f = pyEditorInput.getPath().toFile();
        	
        }else{
        	try {
				IPath path = (IPath) REF.invoke(editorInput, "getPath", new Object[0]);
				f = path.toFile();
			} catch (Exception e) {
				//ok, it has no getPath
			}
        }
        return f;
    }

    // cleanup
    public void dispose() {
    	this.disposed = true;
    	notifier.notifyOnDispose();

        PydevPrefs.getPreferences().removePropertyChangeListener(prefListener);
        parser.dispose();
        colorCache.dispose();
        pyEditScripting = null;
        cache.clear();
        cache = null;
        super.dispose();
    }


    private static final String TEMPLATE_PROPOSALS_ID = "org.python.pydev.editors.PyEdit.TemplateProposals";

    private static final String CONTENTASSIST_PROPOSAL_ID = "org.python.pydev.editors.PyEdit.ContentAssistProposal";

    private static final String CORRECTIONASSIST_PROPOSAL_ID = "org.python.pydev.editors.PyEdit.CorrectionAssist";

    private static final String SIMPLEASSIST_PROPOSAL_ID = "org.python.pydev.editors.PyEdit.SimpleAssist";
    
    public static final int CORRECTIONASSIST_PROPOSALS = 999777;

    public static final int SIMPLEASSIST_PROPOSALS = 999778;
    
    public static class MyResources extends ListResourceBundle {
        public Object[][] getContents() {
            return contents;
        }

        static final Object[][] contents = { { "CorrectionAssist", "CorrectionAssist" }, { "ContentAssistProposal", "ContentAssistProposal" }, { "TemplateProposals", "TemplateProposals" }, };
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.ui.texteditor.AbstractTextEditor#createActions()
     */
    protected void createActions() {
        super.createActions();

        MyResources resources = new MyResources();

        // -------------------------------------------------------------------------------------
        //quick fix in editor.
        IAction action = new TextOperationAction(resources, "CorrectionAssist", this, CORRECTIONASSIST_PROPOSALS); //$NON-NLS-1$

        action.setActionDefinitionId(CORRECTIONASSIST_PROPOSAL_ID);
        setAction(CORRECTIONASSIST_PROPOSAL_ID, action); //$NON-NLS-1$ 
        markAsStateDependentAction(CORRECTIONASSIST_PROPOSAL_ID, true); //$NON-NLS-1$ 
        setActionActivationCode(CORRECTIONASSIST_PROPOSAL_ID, '1', -1, SWT.CTRL);

        
        
        
        // -------------------------------------------------------------------------------------
        //simple assistant for extending later
        action = new TextOperationAction(resources, "SimpleAssist", this, SIMPLEASSIST_PROPOSALS); //$NON-NLS-1$
        
        action.setActionDefinitionId(SIMPLEASSIST_PROPOSAL_ID);
        setAction(SIMPLEASSIST_PROPOSAL_ID, action); //$NON-NLS-1$ 
        setActivationCodeToSimpleCompletion();
        
        
        
        
        // -------------------------------------------------------------------------------------
        // This action will fire a CONTENTASSIST_PROPOSALS operation
        // when executed
        action = new TextOperationAction(resources, "ContentAssistProposal", this, SourceViewer.CONTENTASSIST_PROPOSALS);

        action.setActionDefinitionId(CONTENTASSIST_PROPOSAL_ID);
        // Tell the editor about this new action
        setAction(CONTENTASSIST_PROPOSAL_ID, action);
        // Tell the editor to execute this action
        // when Ctrl+Spacebar is pressed
        setActivationCodeToDefaultCompletion();

        
        // ----------------------------------------------------------------------------------------
        //open action
        IAction openAction = new PyOpenAction();
        setAction(ACTION_OPEN, openAction);
        enableBrowserLikeLinks();
        
        
        // ----------------------------------------------------------------------------------------
        // Offline action
        action= new OfflineAction(resources, "Pyedit.ScriptEngine.", this); 
        action.setActionDefinitionId("org.python.pydev.editor.actions.scriptEngine");
        action.setId("org.python.pydev.editor.actions.scriptEngine");
        setAction("PydevScriptEngine", action);
        
        
        notifier.notifyOnCreateActions(resources);
    }

    /**
     * Used so that Ctrl+Space is binded to the simple assist
     */
    public void setActivationCodeToSimpleCompletion() {
        removeActionActivationCode(CONTENTASSIST_PROPOSAL_ID);
        setActionActivationCode(SIMPLEASSIST_PROPOSAL_ID, ' ', -1, SWT.CTRL);
    }

    /**
     * Used so that Ctrl+Space is binded to the default assist
     */
    public void setActivationCodeToDefaultCompletion() {
        removeActionActivationCode(SIMPLEASSIST_PROPOSAL_ID);
        setActionActivationCode(CONTENTASSIST_PROPOSAL_ID, ' ', -1, SWT.CTRL);
    }


    protected void initializeKeyBindingScopes() {
        setKeyBindingScopes(new String[] { "org.python.pydev.ui.editor.scope" }); //$NON-NLS-1$
    }

    public PyParser getParser() {
        return parser;
    }


    /**
     * Returns the status line manager of this editor.
     * @return the status line manager of this editor
     * @since 2.0
     * 
     * copied from superclass, as it is private there...
     */
    public IStatusLineManager getStatusLineManager() {

        IEditorActionBarContributor contributor= getEditorSite().getActionBarContributor();
        if (!(contributor instanceof EditorActionBarContributor))
            return null;

        IActionBars actionBars= ((EditorActionBarContributor) contributor).getActionBars();
        if (actionBars == null)
            return null;

        return actionBars.getStatusLineManager();
    }

    /**
     * This is the 'offline' action
     */
    protected OfflineActionTarget fOfflineActionTarget;
    
    /**
     * @return an outline view
     */
    public Object getAdapter(Class adapter) {
        if (OfflineActionTarget.class.equals(adapter)) {
            if (fOfflineActionTarget == null) {
                IStatusLineManager manager= getStatusLineManager();
                if (manager != null)
                    fOfflineActionTarget= (getSourceViewer() == null ? null : new OfflineActionTarget(getSourceViewer(), manager, this));
            }
            return fOfflineActionTarget;
        }

        if (IContentOutlinePage.class.equals(adapter)){
            return new PyOutlinePage(this);
        }else{
            return super.getAdapter(adapter);
        }
    }

    /**
     * implementation copied from org.eclipse.ui.externaltools.internal.ant.editor.PlantyEditor#setSelection
     */
    public void setSelection(int offset, int length) {
        ISourceViewer sourceViewer = getSourceViewer();
        sourceViewer.setSelectedRange(offset, length);
        sourceViewer.revealRange(offset, length);
    }

    public void revealModelNode(SimpleNode node) {
        if (node == null){
            return; // nothing to see here
        }
        
        IDocument document = getDocumentProvider().getDocument(getEditorInput());
        if(document == null){
            return;
        }
        
        int offset, length, endOffset;
        
        try {
            PySelection selection = new PySelection(this);
            offset = selection.getLineOffset(node.beginLine-1) + node.beginColumn-1;
            int[] colLineEnd = NodeUtils.getColLineEnd(node);
            
            endOffset = selection.getLineOffset(colLineEnd[0]-1) + colLineEnd[1]-1;
            length = endOffset - offset;
            setSelection(offset, length);
        } catch (Exception e) {
            PydevPlugin.log(e);
        }
        
    }
    /**
     * Selects & reveals the model node
     */
    public void revealModelNode(ASTEntry entry) {
        if (entry == null){
            return; // nothing to see here
        }
        
        IDocument document = getDocumentProvider().getDocument(getEditorInput());
        if(document == null){
        	return;
        }
        
        int offset, length, endOffset;

        try {
            PySelection selection = new PySelection(this);
            offset = selection.getLineOffset(entry.node.beginLine-1) + entry.node.beginColumn-1;
            
            endOffset = selection.getLineOffset(entry.endLine-1) + entry.endCol-1;
            length = endOffset - offset;
            setSelection(offset, length);
        } catch (Exception e) {
            PydevPlugin.log(e);
        }
    }

    /**
     * this event comes when document was parsed without errors
     * 
     * Removes all the error markers
     */
    public void parserChanged(SimpleNode root, IAdaptable file, IDocument doc) {
        // Remove all the error markers
        IEditorInput input = getEditorInput();
        IPath filePath = null;
        
        if(file != null){
            IResource fileAdapter = (IResource) file.getAdapter(IResource.class);
            if(fileAdapter != null){
                filePath = fileAdapter.getLocation();
                
            }else{
                //all this is just to get the file path
                if (input instanceof IFileEditorInput) {
                    IFile file1 = ((IFileEditorInput) input).getFile();
                    filePath = file1.getLocation();
                    
                } else if (input instanceof IStorageEditorInput)
                    try {
                        filePath = ((IStorageEditorInput) input).getStorage().getFullPath();
                        filePath = filePath.makeAbsolute();
                    } catch (CoreException e2) {
                        PydevPlugin.log(IStatus.ERROR, "unexpected error getting path", e2);
                    }
                    
                else if (input instanceof ILocationProvider) {
                    filePath = ((ILocationProvider) input).getPath(input);
                    filePath = filePath.makeAbsolute();
                    
                } else {
                    PydevPlugin.log(IStatus.ERROR, "unexpected type of editor input " + input.getClass().toString(), null);
                }
            }
        }

        int lastLine = doc.getNumberOfLines();
        try {
            doc.getLineInformation(lastLine - 1);
            ast = root;
            fireModelChanged(ast);
        } catch (BadLocationException e1) {
            PydevPlugin.log(IStatus.WARNING, "Unexpected error getting document length. No model!", e1);
        }
        
    }

    /**
     * this event comes when parse ended in an error
     * 
     * generates an error marker on the document
     */
    public void parserError(Throwable error, IAdaptable original, IDocument doc) {
        try {
            if(original == null){
                return;
            }
            IResource fileAdapter = (IResource) original.getAdapter(IResource.class);
            if(fileAdapter == null){
                return;
            }

            int errorStart;
            int errorEnd;
            int errorLine;
            String message;
            if (error instanceof ParseException) {
                ParseException.verboseExceptions = true;
                ParseException parseErr = (ParseException) error;
                
                // Figure out where the error is in the document, and create a
                // marker for it
                if(parseErr.currentToken == null){
                	IRegion endLine = doc.getLineInformationOfOffset(doc.getLength());
                	errorStart = endLine.getOffset();
                	errorEnd = endLine.getOffset() + endLine.getLength();

                }else{
                	Token errorToken = parseErr.currentToken.next != null ? parseErr.currentToken.next : parseErr.currentToken;
	                IRegion startLine = doc.getLineInformation(errorToken.beginLine - 1);
	                IRegion endLine;
	                if (errorToken.endLine == 0){
	                    endLine = startLine;
	                }else{
	                    endLine = doc.getLineInformation(errorToken.endLine - 1);
	                }
	                errorStart = startLine.getOffset() + errorToken.beginColumn - 1;
	                errorEnd = endLine.getOffset() + errorToken.endColumn;
                }
                message = parseErr.getMessage();

            } else {
                TokenMgrError tokenErr = (TokenMgrError) error;
                IRegion startLine = doc.getLineInformation(tokenErr.errorLine - 1);
                errorStart = startLine.getOffset();
                errorEnd = startLine.getOffset() + tokenErr.errorColumn;
                message = tokenErr.getMessage();
            }
            errorLine = doc.getLineOfOffset(errorStart); 

            // map.put(IMarker.LOCATION, "Whassup?"); this is the location field
            // in task manager
            if (message != null) { // prettyprint
                message = message.replaceAll("\\r\\n", " ");
                message = message.replaceAll("\\r", " ");
                message = message.replaceAll("\\n", " ");
            }
            Map<String, Object> map = new HashMap<String, Object>();
            map.put(IMarker.MESSAGE, message);
            map.put(IMarker.SEVERITY, new Integer(IMarker.SEVERITY_ERROR));
            map.put(IMarker.LINE_NUMBER, new Integer(errorLine));
            map.put(IMarker.CHAR_START, new Integer(errorStart));
            map.put(IMarker.CHAR_END, new Integer(errorEnd));
            map.put(IMarker.TRANSIENT, Boolean.valueOf(true));
            MarkerUtilities.createMarker(fileAdapter, map, IMarker.PROBLEM);

        } catch (CoreException e1) {
            // Whatever, could not create a marker. Swallow this one
            PydevPlugin.log(e1);
        } catch (BadLocationException e2) {
            // Whatever, could not create a marker. Swallow this one
        	//PydevPlugin.log(e2);
        }
    }

    public void enableBrowserLikeLinks() {
        if (fMouseListener == null) {
            fMouseListener = new Hyperlink(getSourceViewer(), this, colorCache);
            fMouseListener.install();
        }
    }

    /**
     * Disables browser like links.
     */
    public void disableBrowserLikeLinks() {
        if (fMouseListener != null) {
            fMouseListener.uninstall();
            fMouseListener = null;
        }
    }

    /** stock listener implementation */
    public void addModelListener(IModelListener listener) {
        Assert.isNotNull(listener);
        if (!modelListeners.contains(listener)){
            modelListeners.add(listener);
        }
    }

    /** stock listener implementation */
    public void removeModelListener(IModelListener listener) {
        Assert.isNotNull(listener);
        modelListeners.remove(listener);
    }

    /**
     * stock listener implementation event is fired whenever we get a new root
     * 
     * @param root2
     */
    protected void fireModelChanged(SimpleNode root) {
    	//create a copy, to avoid concurrent modifications
        for (IModelListener listener : new ArrayList<IModelListener>(modelListeners)) {
        	listener.modelChanged(root);
		}
    }

    /**
     * @return the python nature associated with this editor.
     */
    public IPythonNature getPythonNature() {
        IProject project = getProject();
        IPythonNature pythonNature = PythonNature.getPythonNature(project);
        if(pythonNature == null){
            Tuple<SystemPythonNature, String> infoForFile = PydevPlugin.getInfoForFile(getEditorFile());
            if(infoForFile == null){
                NotConfiguredInterpreterException e = new NotConfiguredInterpreterException();
                ErrorDialog.openError(PyAction.getShell(), 
                        "Error: no interpreter configured", "Interpreter not configured\n(Please, Configure it under window->preferences->PyDev)", 
                        PydevPlugin.makeStatus(IStatus.ERROR, e.getMessage(), e));
                throw e;
                
            }
            pythonNature = infoForFile.o1;
        }
        return pythonNature;
    }

    protected void initializeEditor() {
        super.initializeEditor();
        IPreferenceStore prefStore = PydevPlugin.getChainedPrefStore();
        this.setPreferenceStore(prefStore);
        setEditorContextMenuId(PY_EDIT_CONTEXT);
    }

    /**
     * @return
     */
    public SimpleNode getAST() {
        return ast;
    }

    Map<String, ActionInfo> onOfflineActionListeners = new HashMap<String, ActionInfo>();
    public Collection<ActionInfo> getOfflineActionDescriptions(){
        return onOfflineActionListeners.values();
    }
    public void addOfflineActionListener(String key, IAction action) {
        onOfflineActionListeners.put(key, new ActionInfo(action, "not described", key, true));
    }
    public void addOfflineActionListener(String key, IAction action, String description, boolean needsEnter) {
    	onOfflineActionListeners.put(key, new ActionInfo(action, description, key, needsEnter));
	}
    public boolean activatesAutomaticallyOn(String key){
        ActionInfo info = onOfflineActionListeners.get(key);
        if(info != null){
            if(!info.needsEnter){
                return true;
            }
        }
        return false;
    }
    /**
     * @return if an action was binded and was successfully executed
     */
	public boolean onOfflineAction(String requestedStr, OfflineActionTarget target) {
		ActionInfo actionInfo = onOfflineActionListeners.get(requestedStr);
        if(actionInfo == null){
            target.statusError("No action was found binded to:"+requestedStr);
            return false;
            
        }
            
        IAction action = actionInfo.action;
		if(action == null){
		    target.statusError("No action was found binded to:"+requestedStr);
		    return false;
        }
        
		try {
			action.run();
		} catch (Throwable e) {
		    target.statusError("Exception raised when executing action:"+requestedStr+" - "+e.getMessage());
			PydevPlugin.log(e);
			return false;
		}
		return true;
	}
    
    
	public Font getFont(FontData descriptor) {
        Font font = (Font) SWTResourceUtil.getFontTable().get(descriptor);
        if (font == null) {
            font = new Font(Display.getCurrent(), descriptor);
            SWTResourceUtil.getFontTable().put(descriptor, font);
        }
        return font;
    }


}

