/*******************************************************************************
 * Copyright (c) 2006, 2007 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Kyu Lee <klee@redhat.com> - initial API and implementation
 *******************************************************************************/
package org.eclipse.linuxtools.changelog.core.editors;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewerConfiguration;
import org.eclipse.linuxtools.changelog.core.ChangelogPlugin;
import org.eclipse.linuxtools.changelog.core.IEditorChangeLogContrib;
import org.eclipse.linuxtools.changelog.core.Messages;
import org.eclipse.linuxtools.changelog.core.actions.FormatChangeLogAction;
import org.eclipse.ui.editors.text.TextEditor;
import org.eclipse.ui.texteditor.ITextEditorActionConstants;


/**
 * ChangeLog editor that supports GNU format.
 * 
 * @author klee (Kyu Lee)
 */
public class ChangeLogEditor extends TextEditor {

	FormatChangeLogAction fcla;

	public ChangeLogEditor() {
		super();

		fcla = new FormatChangeLogAction(this);

		SourceViewerConfiguration config = getConfig();

		if (config != null) {

			setSourceViewerConfiguration(config);
		} else
			ChangelogPlugin.getDefault().getLog().log(
					new Status(IStatus.ERROR, ChangelogPlugin.PLUGIN_ID, IStatus.ERROR,
							Messages.getString("ChangeLogEditor.ErrConfiguration"), // $NON-NLS-1$
							new Exception(Messages.getString("ChangeLogEditor.ErrConfiguration")))); // $NON-NLS-1$

		setDocumentProvider(new ChangeLogDocumentProvider());

	}

	/**
	 * Gets appropriate style editor from user pref.
	 * 
	 * @return configuration for the Changelog editor
	 */
	
	public SourceViewerConfiguration getConfig() {

		IExtensionPoint editorExtensions = null;
		IEditorChangeLogContrib editorContrib = null;

		// get editor which is stored in preference.
		IPreferenceStore store = ChangelogPlugin.getDefault()
				.getPreferenceStore();
		String pref_Editor = store
				.getString("IChangeLogConstants.DEFAULT_EDITOR"); // $NON-NLS-1$

		editorExtensions = Platform.getExtensionRegistry().getExtensionPoint(
				"org.eclipse.linuxtools.changelog.core", "editorContribution"); //$NON-NLS-1$ //$NON-NLS-2$

		if (editorExtensions != null) {
			IConfigurationElement[] elements = editorExtensions
					.getConfigurationElements();
			for (int i = 0; i < elements.length; i++) {
				if (elements[i].getName().equals("editor") // $NON-NLS-1$ 
						&& (elements[i].getAttribute("name").equals(pref_Editor))) { //$NON-NLS-1$

					try {
						IConfigurationElement bob = elements[i];
						editorContrib = (IEditorChangeLogContrib) bob
								.createExecutableExtension("class"); // $NON-NLS-1$

						editorContrib.setTextEditor(this);
						return (SourceViewerConfiguration) editorContrib;
					} catch (CoreException e) {
						ChangelogPlugin.getDefault().getLog().log(
								new Status(IStatus.ERROR, ChangelogPlugin.PLUGIN_ID,
										IStatus.ERROR, e.getMessage(), e));
					}

				}
			}
		}

		return null;
	}

	public ISourceViewer getMySourceViewer() {
		return this.getSourceViewer();
	}
	
	/**
	 * Specifies context menu.
	 */
	@Override
	protected void editorContextMenuAboutToShow(IMenuManager menu) {

		super.editorContextMenuAboutToShow(menu);
		addAction(menu, ITextEditorActionConstants.GROUP_EDIT,
				ITextEditorActionConstants.SHIFT_RIGHT);
		addAction(menu, ITextEditorActionConstants.GROUP_EDIT,
				ITextEditorActionConstants.SHIFT_LEFT);
		menu.appendToGroup(ITextEditorActionConstants.GROUP_EDIT, fcla);

	}

}
