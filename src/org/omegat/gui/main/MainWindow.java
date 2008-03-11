/**************************************************************************
 OmegaT - Computer Assisted Translation (CAT) tool 
          with fuzzy matching, translation memory, keyword search, 
          glossaries, and translation leveraging into updated projects.

 Copyright (C) 2000-2006 Keith Godfrey, Maxym Mykhalchuk, Henry Pijffers, 
                         Benjamin Siband, and Kim Bruning
               2007 Zoltan Bartko
               2008 Andrzej Sawula
 Portions copyright 2008 Alex Buloichik
               Home page: http://www.omegat.org/
               Support center: http://groups.yahoo.com/group/OmegaT/

 This program is free software; you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation; either version 2 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program; if not, write to the Free Software
 Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
**************************************************************************/

package org.omegat.gui.main;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.event.ComponentListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;

import org.omegat.core.Core;
import org.omegat.core.CoreEvents;
import org.omegat.core.ProjectProperties;
import org.omegat.core.StringEntry;
import org.omegat.core.events.IApplicationEventListener;
import org.omegat.core.events.IProjectEventListener;
import org.omegat.core.matching.NearString;
import org.omegat.core.spellchecker.SpellChecker;
import org.omegat.core.threads.CommandThread;
import org.omegat.gui.ProjectFrame;
import org.omegat.gui.SearchWindow;
import org.omegat.util.LFileCopy;
import org.omegat.util.Log;
import org.omegat.util.OConsts;
import org.omegat.util.OStrings;
import org.omegat.util.Preferences;
import org.omegat.util.RequestPacket;
import org.omegat.util.StaticUtils;
import org.omegat.util.Token;
import org.omegat.util.WikiGet;
import org.omegat.util.gui.OmegaTFileChooser;
import org.omegat.util.gui.ResourcesUtil;
import org.omegat.util.gui.Styles;

import com.vlsolutions.swing.docking.DockingDesktop;

/**
 * The main window of OmegaT application.
 *
 * @author Keith Godfrey
 * @author Benjamin Siband
 * @author Maxym Mykhalchuk
 * @author Kim Bruning
 * @author Henry Pijffers (henry.pijffers@saxnot.com)
 * @author Zoltan Bartko - bartkozoltan@bartkozoltan.com
 * @author Andrzej Sawula
 * @author Alex Buloichik (alex73mail@gmail.com)
 */
public class MainWindow extends JFrame implements ComponentListener, IMainWindow, IProjectEventListener,
        IApplicationEventListener {
    protected final MainWindowMenu menu;
    
    /** Creates new form MainWindow */
    public MainWindow()
    {
        m_searches = new HashSet<SearchWindow>();
        menu = new MainWindowMenu(this, new MainWindowMenuHandler(this));

        setJMenuBar(menu.initComponents());
        getContentPane().add(MainWindowUI.createStatusBar(this), BorderLayout.SOUTH);
        pack();

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                menu.mainWindowMenuHandler.projectExitMenuItemActionPerformed();
            }
        });

        addComponentListener(this);

        MainWindowUI.createMainComponents(this);

        getContentPane().add(MainWindowUI.initDocking(this), BorderLayout.CENTER);

        additionalUIInit();
        oldInit();
        
        MainWindowUI.loadInstantStart(editorScroller, editor);
        
        CoreEvents.registerApplicationEventListener(this);
        CoreEvents.registerProjectChangeListener(this);
    }
    
    public void onApplicationStartup() {
        onProjectChanged();
    }
    
    public void onApplicationShutdown() {
    }

    /**
     * {@inheritDoc}
     */
    public JFrame getApplicationFrame() {
        return this;
    }
    
    /**
     * {@inheritDoc}
     */
    public Font getApplicationFont() {
        return m_font;
    }

    /**
     * Some additional actions to initialize UI,
     * not doable via NetBeans Form Editor
     */
    private void additionalUIInit()
    {
        updateTitle();
        
        setIconImage(ResourcesUtil.getIcon("/org/omegat/gui/resources/OmegaT_small.gif").getImage());

        m_projWin = new ProjectFrame(this);
        m_projWin.setFont(m_font);

        statusLabel.setText(new String()+' ');
        
        MainWindowUI.loadScreenLayout(this);
        uiUpdateOnProjectClose();
    }

    /**
     * Sets the title of the main window appropriately
     */
    public void updateTitle()
    {
        String s = OStrings.getDisplayVersion();
        if(isProjectLoaded())
        {
            s += " :: " + m_activeProj;                                         // NOI18N
            try
            {
                //String file = m_activeFile.substring(CommandThread.core.sourceRoot().length());
                String file = Core.getEditor().getCurrentFile();
 //               Log.log("file = "+file);
                // RFE [1764103] Editor window name 
                editorScroller.setName(StaticUtils.format( 
                OStrings.getString("GUI_SUBWINDOWTITLE_Editor"), 
                                   new Object[] {file})); 
            } catch( Exception e ) { }
        }
        // Fix for bug [1730935] Editor window still shows filename after closing project and
        // RFE [1604238]: instant start display in the main window
        else
        {
            MainWindowUI.loadInstantStart(editorScroller, editor);
        }
        setTitle(s);
    }
    
    /**
     * Old Initialization.
     */
    public void oldInit()
    {
        m_activeProj = new String();
        //m_activeFile = new String();
        
        ////////////////////////////////
        
        enableEvents(0);
        
        // check this only once as it can be changed only at compile time
        // should be OK, but localization might have messed it up
        String start = OConsts.segmentStartStringFull;
        int zero = start.lastIndexOf('0');
        m_segmentTagHasNumber = (zero > 4) && // 4 to reserve room for 10000 digit
                (start.charAt(zero - 1) == '0') &&
                (start.charAt(zero - 2) == '0') &&
                (start.charAt(zero - 3) == '0');
    }
    
    boolean layoutInitialized = false;
    
    public void filelistWindowClosed()
    {
    }
 
    ///////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////
    // command handling

    
   
    /** insert current fuzzy match at cursor position */

    public synchronized void doInsertTrans()
    {
        if (!isProjectLoaded())
            return;
        
        int activeMatch = matches.getActiveMatch();
        if (activeMatch < 0)
            return;
        
        if (activeMatch >= Core.getEditor().getCurrentEntry().getStrEntry().getNearListTranslated().size())
            return;
        
        NearString near = Core.getEditor().getCurrentEntry().getStrEntry().getNearListTranslated().get(activeMatch);
        Core.getEditor().insertText(near.str.getTranslation());
    }

    /** replace entire edit area with active fuzzy match */
    public synchronized void doRecycleTrans()
    {
        if (!isProjectLoaded())
            return;
        
        int activeMatch = matches.getActiveMatch();
        if (activeMatch < 0)
            return;

        if (activeMatch >= Core.getEditor().getCurrentEntry().getStrEntry().getNearListTranslated().size())
            return;
        
        NearString near = Core.getEditor().getCurrentEntry().getStrEntry().getNearListTranslated().get(activeMatch);
        Core.getEditor().replaceEditText(near.str.getTranslation());
    }
    
    /** Closes the project. */
    public void doCloseProject()
    {
        Preferences.save();
        
        if (isProjectLoaded())
            doSave();
        m_projWin.reset();
        synchronized (this) {m_projectLoaded = false;}

        synchronized (this) {
            editor.setText(OStrings.getString("TF_INTRO_MESSAGE"));
        }
        matches.clear();
        glossary.clear();
        
        updateTitle();
        uiUpdateOnProjectClose();
        
        Core.getDataEngine().closeProject();
        showProgressMessage(OStrings.getString("MW_PROGRESS_DEFAULT"));
        showLengthMessage(OStrings.getString("MW_SEGMENT_LENGTH_DEFAULT"));
    }
    
    public void onProjectChanged() {
        if (Core.getDataEngine().isProjectLoaded()) {
            menu.onProjectStatusChanged(true);
        } else {
            menu.onProjectStatusChanged(false);
        }
    }
    
    /** Updates UI (enables/disables menu items) upon <b>closing</b> project */
    private void uiUpdateOnProjectClose()
    {
        synchronized (editor) {
            editor.setEditable(false);
        }

        // hide project file list
        m_projWin.uiUpdateImportButtonStatus();
        m_projWin.setVisible(false);

        // dispose other windows
        for (SearchWindow sw : m_searches) {
            sw.dispose();
        }
        m_searches.clear();
    }
    
    /** Updates UI (enables/disables menu items) upon <b>opening</b> project */
    private void uiUpdateOnProjectOpen()
    {
        synchronized (editor) {
            editor.setEditable(true);
        }
        
        updateTitle();
        m_projWin.buildDisplay();
        
        m_projWin.uiUpdateImportButtonStatus();
        
        m_projWin.setVisible(true);
    }
    
    /**
     * Notifies Main Window that the CommandThread has finished loading the 
     * project.
     * <p>
     * Current implementation commits and re-activates current entry to show 
     * fuzzy matches.
     * <p>
     * Calling Main Window back to notify that project is successfully loaded.
     * Part of bugfix for 
     * <a href="http://sourceforge.net/support/tracker.php?aid=1370838">[1370838]
     * First segment does not trigger matches after load</a>.
     */
    public synchronized void projectLoaded()
    {
        Thread runlater = new Thread()
        {
            public void run()
            {
                updateFuzzyInfo();    // just display the matches, don't commit/activate!
                updateGlossaryInfo(); // and glossary matches
                // commitEntry(false); // part of fix for bug 1409309
                // activateEntry();
            }
        };
        SwingUtilities.invokeLater(runlater);
    }

    void doSave()
    {
        if (!isProjectLoaded())
            return;
        
        showStatusMessage(OStrings.getString("MW_STATUS_SAVING"));
        
        Core.getDataEngine().saveProject();
        
        showStatusMessage(OStrings.getString("MW_STATUS_SAVED"));
    }
    
    /**
     * Creates a new Project.
     */
    void doCreateProject()
    {
        Core.getDataEngine().createProject();
        try
        {
            String projectRoot = CommandThread.core.getProjectProperties().getProjectRoot();
            if( new File(projectRoot).exists() )
                doLoadProject(projectRoot);
        }
        catch( Exception e )
        {
            // do nothing
        }
    }
    
    /**
     * Loads a new project.
     */
    void doLoadProject()
    {
        if (isProjectLoaded())
        {
            displayError( "Please close the project first!", new Exception( "Another project is open")); // NOI18N
            return;
        }

        matches.clear();
        glossary.clear();
        history.clear();
        editorScroller.setViewportView(editor);

        RequestPacket load;
        load = new RequestPacket(RequestPacket.LOAD, this);
        CommandThread.core.messageBoardPost(load);
    }
    
    /**
     * Loads the same project as was open in OmegaT before.
     * @param projectRoot previously closed project's root
     */
    public void doLoadProject(String projectRoot)
    {
        if (isProjectLoaded())
        {
            displayError( "Please close the project first!", new Exception( "Another project is open")); // NOI18N
            return;
        }

        matches.clear();
        glossary.clear();
        history.clear();
        editorScroller.setViewportView(editor);

        RequestPacket load;
        load = new RequestPacket(RequestPacket.LOAD, this, projectRoot);
        CommandThread.core.messageBoardPost(load);
    }

    /**
     * Reloads, i.e. closes and loads the same project.
     */
    public void doReloadProject()
    {
        ProjectProperties config = CommandThread.core.getProjectProperties();
        String projectRoot = config.getProjectRoot();
        doCloseProject();
        doLoadProject(projectRoot);
    }
    
    
    /**
     * Imports the file/files/folder into project's source files.
     * @author Kim Bruning
     * @author Maxym Mykhalchuk
     */
    public void doImportSourceFiles()
    {
        OmegaTFileChooser chooser=new OmegaTFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        
        int result=chooser.showOpenDialog(this);
        if( result==OmegaTFileChooser.APPROVE_OPTION )
        {
            String projectsource = CommandThread.core.getProjectProperties().getSourceRoot();
            File sourcedir = new File(projectsource);
            File[] selFiles=chooser.getSelectedFiles();
            try
            {
                for(int i=0;i<selFiles.length;i++)
                {
                    File selSrc=selFiles[i];
                    if( selSrc.isDirectory() )
                    {
                        List<String> files = new ArrayList<String>();
                        StaticUtils.buildFileList(files, selSrc, true);
                        String selSourceParent = selSrc.getParent();
                        for(String filename : files)
                        {
                            String midName = filename.substring(selSourceParent.length());
                            File src=new File(filename);
                            File dest=new File(sourcedir, midName);
                            LFileCopy.copy(src, dest);
                        }
                    }
                    else
                    {
                        File dest=new File(sourcedir, selFiles[i].getName());
                        LFileCopy.copy(selSrc, dest);
                    }
                }
                doReloadProject();
            }
            catch(IOException ioe)
            {
                displayError(OStrings.getString("MAIN_ERROR_File_Import_Failed"), ioe);
            }
        }
        
    }

    /** 
    * Does wikiread 
    * @author Kim Bruning
    */
    public void doWikiImport()
    {
        String remote_url = JOptionPane.showInputDialog(this,
                OStrings.getString("TF_WIKI_IMPORT_PROMPT"), 
		OStrings.getString("TF_WIKI_IMPORT_TITLE"),
		JOptionPane.OK_CANCEL_OPTION);
        String projectsource = 
                CommandThread.core.getProjectProperties().getSourceRoot();
         // [1762625] Only try to get MediaWiki page if a string has been entered 
        if ( (remote_url != null ) && (remote_url.trim().length() > 0) )
        {
            WikiGet.doWikiGet(remote_url, projectsource);
            doReloadProject();
        }
    }
    
    public synchronized void finishLoadProject()
    {
        SwingUtilities.invokeLater(new Runnable()
        {
            public synchronized void run()
            {
                m_activeProj = CommandThread.core.getProjectProperties().getProjectName();
                //m_activeFile = new String();
                Core.getEditor().setFirstEntry();
                
                Core.getEditor().loadDocument();
                synchronized (this) {m_projectLoaded = true;}
                
                uiUpdateOnProjectOpen();
            }
        });
    }
    
    public void searchWindowClosed(SearchWindow searchWindow) {
        m_searches.remove(searchWindow);
    }

    /**
     * Show message in status bar.
     * 
     * @param str
     *                message text
     */
    public void showStatusMessage(String str) {
        if (str.length() == 0)
            str = new String() + ' ';
        statusLabel.setText(str);
    }
    
    /**
     * Show message in progress bar.
     * 
     * @param messageText
     *                message text
     */
    public void showProgressMessage(String messageText) {
        progressLabel.setText(messageText);
    }

    /**
     * Show message in length label.
     * 
     * @param messageText
     *                message text
     */
    public void showLengthMessage(String messageText) {
        // TODO Auto-generated method stub

    }

    ///////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////
    // display oriented code
    
    /**
     * Displays fuzzy matching info if it's available.
     */
    public void updateFuzzyInfo()
    {
        if (!isProjectLoaded())
            return;
        
        StringEntry curEntry = Core.getEditor().getCurrentEntry().getStrEntry();
        matches.setMatches(curEntry.getNearListTranslated());
    }
    
    /**
     * Displays glossary terms for the current segment.
     */
    public void updateGlossaryInfo()
    {
        StringEntry curEntry = Core.getEditor().getCurrentEntry().getStrEntry();
        glossary.setGlossaryEntries(curEntry.getGlossaryEntries());
    }
    
    /** Is any segment edited currently? */
    public boolean entryActivated = false;
    
    public static final String IMPOSSIBLE = "Should not have happened, " +     // NOI18N
            "report to http://sf.net/tracker/?group_id=68187&atid=520347";      // NOI18N

    public final int WITH_END_MARKERS = 1;
    public final int IS_NOT_TRANSLATED = 2;
    
    /**
     * Displays a warning message.
     *
     * @param msg the message to show
     * @param e exception occured. may be null
     */
    public void displayWarning(String msg, Throwable e)
    {
	showStatusMessage(msg);
        String fulltext = msg;
        if( e!=null )
            fulltext+= "\n" + e.toString();                                     // NOI18N
        JOptionPane.showMessageDialog(this, fulltext, OStrings.getString("TF_WARNING"),
                JOptionPane.WARNING_MESSAGE);
    }
    
    /**
     * Displays an error message.
     *
     * @param msg the message to show
     * @param e exception occured. may be null
     */
    public void displayError(String msg, Throwable e)
    {
	showStatusMessage(msg);
        String fulltext = msg;
        if( e!=null )
            fulltext+= "\n" + e.toString();                                     // NOI18N
        JOptionPane.showMessageDialog(this, fulltext, OStrings.getString("TF_ERROR"),
                JOptionPane.ERROR_MESSAGE);
    }

    /**
     * Checks the spelling of the segment.
     * @param start : the starting position
     * @param text : the text to check
     */
    public synchronized List<Token> checkSpelling(int start, String text) {
        // we have the translation and it should be spellchecked
        List<Token> wordlist = StaticUtils.tokenizeText(text);
        List<Token> wrongWordList = new ArrayList<Token>();
        
        AbstractDocument xlDoc = (AbstractDocument)editor.getDocument();
        AttributeSet attributes = m_translatedAttributeSet;

        SpellChecker spellchecker = CommandThread.core.getSpellchecker();

        for (Token token : wordlist) {
            int tokenStart = token.getOffset();
            int tokenEnd = tokenStart + token.getLength();
            String word = text.substring(tokenStart, tokenEnd);

            if (!spellchecker.isCorrect(word)) {
                try {
                    xlDoc.replace(
                            start+tokenStart,
                            token.getLength(),
                            word,
                            Styles.applyStyles(attributes,Styles.MISSPELLED)
                            );
                } catch (BadLocationException ble) {
                    //Log.log(IMPOSSIBLE);
                    Log.log(ble);
                }
                wrongWordList.add(token);
            }
        }
        return wrongWordList;
    }
    
    public void fatalError(String msg, Throwable re)
    {
        Log.log(msg);
        if (re != null)
            Log.log(re);

        // try for 10 seconds to shutdown gracefully
        CommandThread.core.interrupt();
        for( int i=0; i<100 && CommandThread.core!=null; i++ )
        {
            try
            {
                Thread.sleep(100);
            }
            catch (InterruptedException e)
            {
            }
        }
        Runtime.getRuntime().halt(1);
    }
    
    /** Tells whether the project is loaded. */
    public synchronized boolean isProjectLoaded()
    {
        return m_projectLoaded;
    }
    
    /** The font for main window (source and target text) and for match and glossary windows */
    Font m_font;
    
    // boolean set after safety check that org.omegat.OConsts.segmentStartStringFull
    //	contains empty "0000" for segment number
    public boolean	m_segmentTagHasNumber;
    
    public char	m_advancer;

    private String  m_activeProj;

    ProjectFrame m_projWin;
    public ProjectFrame getProjectFrame()
    {
        return m_projWin;
    }
    
    /**
     * the attribute set used for translated segments
     */
    AttributeSet m_translatedAttributeSet;
    
    /**
     * the attribute set used for translated segments
     */
    AttributeSet m_unTranslatedAttributeSet;
    
    /**
     * return the attribute set of translated segments
     */
    public AttributeSet getTranslatedAttributeSet() {
        return m_translatedAttributeSet;
    }
    
    /**
     * display the segmetn sources or not
     */
    boolean m_displaySegmentSources;
    
    public boolean displaySegmentSources() {
        return m_displaySegmentSources;
    }
    
    Set<SearchWindow> m_searches; // set of all open search windows
    
    public boolean m_projectLoaded;

    public void componentHidden(java.awt.event.ComponentEvent evt) {
    }

    public void componentMoved(java.awt.event.ComponentEvent evt) {
        if (evt.getSource() == MainWindow.this) {
            MainWindow.this.formComponentMoved(evt);
        }
    }

    public void componentResized(java.awt.event.ComponentEvent evt) {
        if (evt.getSource() == MainWindow.this) {
            MainWindow.this.formComponentResized(evt);
        }
    }

    public void componentShown(java.awt.event.ComponentEvent evt) {
    }

    public void formComponentMoved(java.awt.event.ComponentEvent evt)
    {
        MainWindowUI.saveScreenLayout(this);
    }
    
    public void formComponentResized(java.awt.event.ComponentEvent evt)
    {
        MainWindowUI.saveScreenLayout(this);
    }
    
    boolean m_autoSpellChecking;
    
    public boolean autoSpellCheckingOn() {
        return m_autoSpellChecking;
    }
    
    JLabel lengthLabel;    
    JLabel progressLabel;    
    JLabel statusLabel;

    DockingDesktop desktop;

    DockableScrollPane editorScroller;
    public EditorTextArea editor;
    
    DockableScrollPane matchesScroller;
    MatchesTextArea matches;
    
    DockableScrollPane glossaryScroller;
    GlossaryTextArea glossary;
    
    SegmentHistory history = SegmentHistory.getInstance();
}
