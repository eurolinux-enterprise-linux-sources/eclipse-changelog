/*******************************************************************************
 * Copyright (c) 2006-2008 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Kyu Lee <klee@redhat.com> - initial API and implementation
 *    Jeff Johnston <jjohnstn@redhat.com> - remove CVS bindings, support removal
 *******************************************************************************/
package org.eclipse.linuxtools.changelog.core.actions;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Vector;

import org.eclipse.compare.rangedifferencer.RangeDifference;
import org.eclipse.compare.rangedifferencer.RangeDifferencer;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.compare.structuremergeviewer.IDiffElement;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IStorage;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.linuxtools.changelog.core.ChangeLogWriter;
import org.eclipse.linuxtools.changelog.core.ChangelogPlugin;
import org.eclipse.linuxtools.changelog.core.IParserChangeLogContrib;
import org.eclipse.linuxtools.changelog.core.LineComparator;
import org.eclipse.linuxtools.changelog.core.Messages;
import org.eclipse.team.core.RepositoryProvider;
import org.eclipse.team.core.diff.IDiff;
import org.eclipse.team.core.diff.IThreeWayDiff;
import org.eclipse.team.core.history.IFileRevision;
import org.eclipse.team.core.mapping.IResourceDiff;
import org.eclipse.team.core.subscribers.Subscriber;
import org.eclipse.team.core.synchronize.SyncInfo;
import org.eclipse.team.core.synchronize.SyncInfoSet;
import org.eclipse.team.ui.synchronize.ISynchronizeModelElement;
import org.eclipse.ui.IActionDelegate;
import org.eclipse.ui.IEditorDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.editors.text.FileDocumentProvider;
import org.eclipse.ui.editors.text.StorageDocumentProvider;
import org.eclipse.ui.part.FileEditorInput;



/**
 * Action handler for prepare changelog.
 * 
 * @author klee
 * 
 */
public class PrepareChangeLogAction extends ChangeLogAction {

	/**
	 * Provides IDocument given editor input
	 * 
	 * @author klee
	 * 
	 */
	
	private class MyDocumentProvider extends FileDocumentProvider {

		@Override
		public IDocument createDocument(Object element) throws CoreException {
			return super.createDocument(element);
		}
	}

	private class MyStorageDocumentProvider extends StorageDocumentProvider {

		@Override
		public IDocument createDocument(Object element) throws CoreException {
			return super.createDocument(element);
		}
	}

	public PrepareChangeLogAction(String name) {
		super(name);
	}

	private IStructuredSelection selected;

	public PrepareChangeLogAction() {
		super();
	}

	protected void setSelection(IStructuredSelection selection) {
		this.selected = selection;
	}

	private String parseCurrentFunctionAtOffset(String editorName,
			IEditorInput input, int offset) {

		IParserChangeLogContrib parser = extensionManager
				.getParserContributor(editorName);

		// return empty string if function parser for editorName is not present
		if (parser==null)
			return "";
		
		try {
			return parser.parseCurrentFunction(input, offset);
		} catch (CoreException e) {
			ChangelogPlugin.getDefault().getLog().log(
					new Status(IStatus.ERROR, ChangelogPlugin.PLUGIN_ID, IStatus.ERROR, e
							.getMessage(), e));
		}
		return "";
	}

	/**
	 * @see IActionDelegate#run(IAction)
	 */
	protected void doRun() {
		IRunnableWithProgress code = new IRunnableWithProgress() {

			public void run(IProgressMonitor monitor)
					throws InvocationTargetException, InterruptedException {
				monitor.beginTask(Messages.getString("ChangeLog.PrepareChangeLog"), 1000); // $NON-NLS-1$
				prepareChangeLog(monitor);
				monitor.done();
			}
		};

		ProgressMonitorDialog pd = new ProgressMonitorDialog(getWorkbench()
				.getActiveWorkbenchWindow().getShell());

		try {
			pd.run(false /* fork */, false /* cancelable */, code);
		} catch (InvocationTargetException e) {
			ChangelogPlugin.getDefault().getLog().log(
					new Status(IStatus.ERROR, ChangelogPlugin.PLUGIN_ID, IStatus.ERROR, e
							.getMessage(), e));
			return;
		} catch (InterruptedException e) {
			ChangelogPlugin.getDefault().getLog().log(
					new Status(IStatus.ERROR, ChangelogPlugin.PLUGIN_ID, IStatus.ERROR, e
							.getMessage(), e));
		}
	}

	private void extractSynchronizeModelInfo (ISynchronizeModelElement d, IPath path, Vector<PatchFile> newList, Vector<PatchFile> removeList, Vector<PatchFile> changeList) {
		// Recursively traverse the tree for children and sort leaf elements into their respective change kind sets.
		// Don't add entries for ChangeLog files though.
		if (d.hasChildren()) {
			IPath newPath = path.append(d.getName());
			for (IDiffElement element: d.getChildren()) {
				if (element instanceof ISynchronizeModelElement)
					extractSynchronizeModelInfo((ISynchronizeModelElement)element, newPath, newList, removeList, changeList);
				else if (!(d.getName().equals("ChangeLog"))) { // $NON-NLS-1$
					IPath finalPath = path.append(d.getName());
					PatchFile p = new PatchFile(finalPath);
					int kind = d.getKind() & Differencer.CHANGE_TYPE_MASK;
					if (kind == Differencer.CHANGE) {
						changeList.add(p);
						// Save the resource so we can later figure out what lines changed
						p.setResource(d.getResource());
					}
					else if (kind == Differencer.ADDITION) {
						p.setNewfile(true);
						newList.add(p);
					}
					else if (kind == Differencer.DELETION) {
						p.setRemovedFile(true);
						removeList.add(p);
					}
				}
			}
		} else if (!(d.getName().equals("ChangeLog"))) { // $NON-NLS-1$
			IPath finalPath = path.append(d.getName());
			PatchFile p = new PatchFile(finalPath);
			int kind = d.getKind() & Differencer.CHANGE_TYPE_MASK;
			if (kind == Differencer.CHANGE) {
				changeList.add(p);
				// Save the resource so we can later figure out what lines changed
				p.setResource(d.getResource());
			}
			else if (kind == Differencer.ADDITION) {
				p.setNewfile(true);
				newList.add(p);
			}
			else if (kind == Differencer.DELETION) {
				p.setRemovedFile(true);
				removeList.add(p);
			}
		}
	}

	private void getChangedLines(Subscriber s, PatchFile p, IProgressMonitor monitor) {
		try {
			// For an outgoing changed resource, find out which lines
			// differ from the local file and its previous local version
			// (i.e. we don't want to force a diff with the repository).
			IDiff d = s.getDiff(p.getResource());
			if (d instanceof IThreeWayDiff
					&& ((IThreeWayDiff)d).getDirection() == IThreeWayDiff.OUTGOING) {
				IThreeWayDiff diff = (IThreeWayDiff)d;
				monitor.beginTask(null, 100);
				IResourceDiff localDiff = (IResourceDiff)diff.getLocalChange();
				IResource resource = localDiff.getResource();
				if (resource instanceof IFile) {
					IFile file = (IFile)resource;
					monitor.subTask(Messages.getString("ChangeLog.MergingDiffs")); // $NON-NLS-1$
					String osEncoding = file.getCharset();
					IFileRevision ancestorState = localDiff.getBeforeState();
					IStorage ancestorStorage;
					if (ancestorState != null) {
						ancestorStorage = ancestorState.getStorage(monitor);
						p.setStorage(ancestorStorage);
					}
					else 
						ancestorStorage = null;

					try {
						// We compare using a standard differencer to get ranges
						// of changes.  We modify them to be document-based (i.e.
						// first line is line 1) and store them for later parsing.
						LineComparator left = new LineComparator(ancestorStorage.getContents(), osEncoding);
						LineComparator right = new LineComparator(file.getContents(), osEncoding);
						for (RangeDifference tmp: RangeDifferencer.findDifferences(left, right)) {
							if (tmp.kind() == RangeDifference.CHANGE) {
								// Right side of diff are all changes found in local file.
								int rightLength = tmp.rightLength() > 0 ? tmp.rightLength() : tmp.rightLength() + 1;
								// We also want to store left side of the diff which are changes to the ancestor as it may contain
								// functions/methods that have been removed.
								int leftLength = tmp.leftLength() > 0 ? tmp.leftLength() : tmp.leftLength() + 1;
								// Only store left side changes if the storage exists and we add one to the start line number
								if (p.getStorage() != null)
									p.addLineRange(tmp.leftStart(), tmp.leftStart() + leftLength, false);
								p.addLineRange(tmp.rightStart(), tmp.rightStart() + rightLength, true);
							}
						}
					} catch (UnsupportedEncodingException e) {
						// do nothing for now
					}
				}
				monitor.done();
			}
		} catch (CoreException e) {
			// Do nothing if error occurs
		}
	}
	
	private void prepareChangeLog(IProgressMonitor monitor) {

		Object element = selected.getFirstElement();
		
		IResource resource = null;
		Vector<PatchFile> newList = new Vector<PatchFile>();
		Vector<PatchFile> removeList = new Vector<PatchFile>();
		Vector<PatchFile> changeList = new Vector<PatchFile>();
		int totalChanges = 0;
		
		if (element instanceof IResource) {
			resource = (IResource)element;
		} else if (element instanceof ISynchronizeModelElement) {
			ISynchronizeModelElement sme = (ISynchronizeModelElement)element;
			resource = sme.getResource();
		} else if (element instanceof IAdaptable) {
			resource = (IResource)((IAdaptable)element).getAdapter(IResource.class);
		}

		if (resource == null)
			return;

		IProject project = resource.getProject();

		// Get the repository provider so we can support multiple types of
		// code repositories without knowing exactly which (e.g. CVS, SVN, etc..).
		RepositoryProvider r = RepositoryProvider.getProvider(project);
		if (r == null)
			return;
		SyncInfoSet set = new SyncInfoSet();
		Subscriber s = r.getSubscriber();
		if (s == null)
			return;
		if (element instanceof ISynchronizeModelElement) {
			// We can extract the ChangeLog list from the synchronize view which
			// allows us to skip items removed from the view
			ISynchronizeModelElement d = (ISynchronizeModelElement)element;
			while (d.getParent() != null)
				d = (ISynchronizeModelElement)d.getParent();
			extractSynchronizeModelInfo(d, new Path(""), newList, removeList, changeList);
			totalChanges = newList.size() + removeList.size() + changeList.size();
		}
		else {
			// We can then get a list of all out-of-sync resources.
			s.collectOutOfSync(new IResource[] {project}, IResource.DEPTH_INFINITE, set, monitor);
			SyncInfo[] infos = set.getSyncInfos();
			totalChanges = infos.length;
			// Iterate through the list of changed resources and categorize them into
			// New, Removed, and Changed lists.
			for (SyncInfo info : infos) {
				int kind = SyncInfo.getChange(info.getKind());
				PatchFile p = new PatchFile(info.getLocal().getFullPath());

				// Check the type of entry and sort into lists.  Do not add an entry
				// for ChangeLog files.
				if (!(p.getPath().lastSegment().equals("ChangeLog"))) { // $NON-NLS-1$
					if (kind == SyncInfo.ADDITION) {
						p.setNewfile(true);
						newList.add(p);
					} else if (kind == SyncInfo.DELETION) {
						p.setRemovedFile(true);
						removeList.add(p);
					} else if (kind == SyncInfo.CHANGE) {
						if (info.getLocal().getType() == IResource.FILE) {
							changeList.add(p);
							// Save the resource so we can later figure out which lines were changed
							p.setResource(info.getLocal());
						}
					}
				}
			}
		}
		
		if (totalChanges == 0)
			return; // nothing to parse
		
		PatchFile[] patchFileInfoList = new PatchFile[totalChanges];
	
		// Group like changes together and sort them by path name.
		// We want removed files, then new files, then changed files.
		// To get this, we put them in the array in reverse order.
		int index = 0;
		if (changeList.size() > 0) {
			// Get the repository provider so we can support multiple types of
			// code repositories without knowing exactly which (e.g. CVS, SVN, etc..).
			Collections.sort(changeList, new PatchFileComparator());
			int size = changeList.size();
			for (int i = 0; i < size; ++i) {
				PatchFile p = changeList.get(i);
				getChangedLines(s, p, monitor);
				patchFileInfoList[index+(size-i-1)] = p;
			}
			index += size;
		}
		
		if (newList.size() > 0) {
			Collections.sort(newList, new PatchFileComparator());
			int size = newList.size();
			for (int i = 0; i < size; ++i)
				patchFileInfoList[index+(size-i-1)] = newList.get(i);
			index += size;
		}
		
		if (removeList.size() > 0) {
			Collections.sort(removeList, new PatchFileComparator());
			int size = removeList.size();
			for (int i = 0; i < size; ++i)
				patchFileInfoList[index+(size-i-1)] = removeList.get(i);
		}
	
		// now, find out modified functions/classes.
		// try to use the the extension point. so it can be extended easily
		// for all files in patch file info list, get function guesses of each
		// file.
		monitor.subTask(Messages.getString("ChangeLog.WritingMessage")); // $NON-NLS-1$
		int unitwork = 250 / patchFileInfoList.length;
		for (PatchFile pf: patchFileInfoList) {
			// for each file
			if (pf != null) { // any ChangeLog changes will have null entries for them
				String[] funcGuessList = guessFunctionNames(pf);
				outputMultipleEntryChangeLog(pf, funcGuessList);
			}
			monitor.worked(unitwork);
		}
	}

	protected IEditorPart changelog;

	public void outputMultipleEntryChangeLog(PatchFile pf, String[] functionGuess) {

		String defaultContent = null;
		
		if (pf.isNewfile())
			defaultContent = Messages.getString("ChangeLog.NewFile"); // $NON-NLS-1$
		else if (pf.isRemovedFile())
			defaultContent = Messages.getString("ChangeLog.RemovedFile"); // $NON-NLS-1$

		IPath entryPath = pf.getPath();
		String entryFileName = entryPath.toOSString();
		
		ChangeLogWriter clw = new ChangeLogWriter();

		// load settings from extensions + user pref.
		loadPreferences();

		// get file path from target file
		clw.setEntryFilePath(entryPath.toOSString());
		
		if (defaultContent != null)
			clw.setDefaultContent(defaultContent);

		// err check. do nothing if no file is being open/edited
		if (clw.getEntryFilePath() == "") {
			return;
		}

		// get formatter
		clw.setFormatter(extensionManager.getFormatterContributor(
				entryFileName, pref_Formatter));

		IEditorPart changelog = null;

		if (pf.isRemovedFile())
			changelog = getChangelogForRemovePath(entryPath);
		else
			changelog = getChangelog(entryFileName);

		// FIXME: this doesn't seem very useful or probable
		if (changelog == null)
			changelog = askChangeLogLocation(entryFileName);

		if (changelog == null) {
			ChangelogPlugin.getDefault().getLog().log(
					new Status(IStatus.ERROR, ChangelogPlugin.PLUGIN_ID, IStatus.ERROR, // $NON-NLS-1$
							Messages.getString("ChangeLog.ErrNoChangeLog"), null)); // $NON-NLS-1$
			return;
		}

		// select changelog
		clw.setChangelog(changelog);

		// write to changelog

		clw.setDateLine(clw.getFormatter().formatDateLine(pref_AuthorName,
				pref_AuthorEmail));

		clw.setChangelogLocation(getDocumentLocation(clw.getChangelog(), true));

		// print multiple changelog entries with different
		// function guess names.  We default to an empty guessed name
		// if we have zero function guess names.
		int numFuncs = 0;
		clw.setGuessedFName(""); // $NON-NLS-1$
		if (functionGuess.length > 0) {
			for (String guess : functionGuess) {
				if (!guess.trim().equals("")) { // $NON-NLS-1$
					++numFuncs;
					clw.setGuessedFName(guess);
					clw.writeChangeLog();
				}
			}
		}
		// Default an empty entry if we did not have any none-empty
		// function guesses.
		if (numFuncs == 0) {
			clw.writeChangeLog();
		}

	}


	/**
	 * Guesses the function effected/modified by the patch from local file(newer
	 * file).
	 * 
	 * @param patchFileInfo
	 *            patch file
	 * @return array of unique function names
	 */
	private String[] guessFunctionNames(PatchFile patchFileInfo) {

		
		// if this file is new file or removed file, do not guess function files
		// TODO: create an option to include function names on 
		// new files or not
		if (patchFileInfo.isNewfile() || patchFileInfo.isRemovedFile()) {
			return new String[]{""};
		}

		String[] fnames = new String[0];
		String editorName = ""; // $NON-NLS-1$

		try {
			IEditorDescriptor ed = org.eclipse.ui.ide.IDE
					.getEditorDescriptor(patchFileInfo.getPath().toOSString());
			editorName = ed.getId().substring(ed.getId().lastIndexOf(".") + 1); // $NON-NLS-1$
		} catch (PartInitException e1) {
			ChangelogPlugin.getDefault().getLog().log(
					new Status(IStatus.ERROR, ChangelogPlugin.PLUGIN_ID, IStatus.ERROR,
							e1.getMessage(), e1));
			return new String[0];
		}

		// check if the file type is supported

		// get editor input for target file
		FileEditorInput fei = new FileEditorInput(getWorkspaceRoot()
				.getFileForLocation(
						getWorkspaceRoot().getLocation().append(
								patchFileInfo.getPath())));
		
		SourceEditorInput sei = new SourceEditorInput(patchFileInfo.getStorage());

		MyDocumentProvider mdp = new MyDocumentProvider();
		MyStorageDocumentProvider msdp = new MyStorageDocumentProvider();

		try {
			// get document for target file (one for local file, one for repository storage)
			IDocument doc = mdp.createDocument(fei);
			IDocument olddoc = msdp.createDocument(sei);

			HashMap<String, String> functionNamesMap = new HashMap<String, String>();
			ArrayList<String> nameList = new ArrayList<String>();

			// for all the ranges
			for (PatchRangeElement tpre: patchFileInfo.getRanges()) {

					for (int j = tpre.ffromLine; j <= tpre.ftoLine; j++) {

						if ((j < 0) || (j > doc.getNumberOfLines() - 1))
							continue; // ignore out of bound lines

						String functionGuess = "";
						// add func that determines type of file.
						// right now it assumes it's java file.
						if (tpre.isLocalChange())
							functionGuess = parseCurrentFunctionAtOffset(
									editorName, fei, doc.getLineOffset(j));
						else
							functionGuess = parseCurrentFunctionAtOffset(
									editorName, sei, olddoc.getLineOffset(j));

						// putting it in hashmap will eliminate duplicate
						// guesses.  We use a list to keep track of ordering which
						// is helpful when trying to document a large set of changes.
						if (functionNamesMap.get(functionGuess) == null)
							nameList.add(functionGuess);
						functionNamesMap.put(functionGuess, functionGuess);
					}
			}

			// dump all unique func. guesses in the order found
			fnames = new String[nameList.size()];
			fnames = nameList.toArray(fnames);
		
		} catch (CoreException e) {
			ChangelogPlugin.getDefault().getLog().log(
					new Status(IStatus.ERROR, ChangelogPlugin.PLUGIN_ID, IStatus.ERROR,
							e.getMessage(), e));
		} catch (BadLocationException e) {
			ChangelogPlugin.getDefault().getLog().log(
					new Status(IStatus.ERROR, ChangelogPlugin.PLUGIN_ID, IStatus.ERROR,
							e.getMessage(), e));
		} catch (Exception e) {
			ChangelogPlugin.getDefault().getLog().log(
					new Status(IStatus.ERROR, ChangelogPlugin.PLUGIN_ID, IStatus.ERROR,
							e.getMessage(), e));
		}
		return fnames;
	}
}