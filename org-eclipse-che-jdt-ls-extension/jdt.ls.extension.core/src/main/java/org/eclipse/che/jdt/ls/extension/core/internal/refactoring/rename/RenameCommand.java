/*
 * Copyright (c) 2012-2018 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.jdt.ls.extension.core.internal.refactoring.rename;

import static org.eclipse.che.jdt.ls.extension.core.internal.ChangeUtil.convertRefactoringStatus;
import static org.eclipse.che.jdt.ls.extension.core.internal.Utils.ensureNotCancelled;

import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import java.util.List;
import org.eclipse.che.jdt.ls.extension.api.RenameKind;
import org.eclipse.che.jdt.ls.extension.api.dto.CheWorkspaceEdit;
import org.eclipse.che.jdt.ls.extension.api.dto.RefactoringResult;
import org.eclipse.che.jdt.ls.extension.api.dto.RenameSettings;
import org.eclipse.che.jdt.ls.extension.core.internal.ChangeUtil;
import org.eclipse.che.jdt.ls.extension.core.internal.GsonUtils;
import org.eclipse.che.jdt.ls.extension.core.internal.JavaModelUtil;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.ls.core.internal.JDTUtils;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.corext.refactoring.rename.RenamePackageProcessor;
import org.eclipse.jdt.ls.core.internal.corext.refactoring.rename.RenameSupport;
import org.eclipse.jdt.ls.core.internal.corext.refactoring.tagging.IDelegateUpdating;
import org.eclipse.jdt.ls.core.internal.corext.refactoring.tagging.IQualifiedNameUpdating;
import org.eclipse.jdt.ls.core.internal.corext.refactoring.tagging.IReferenceUpdating;
import org.eclipse.jdt.ls.core.internal.corext.refactoring.tagging.ISimilarDeclarationUpdating;
import org.eclipse.jdt.ls.core.internal.corext.refactoring.tagging.ITextUpdating;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CheckConditionsOperation;
import org.eclipse.ltk.core.refactoring.CreateChangeOperation;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.participants.RefactoringProcessor;
import org.eclipse.ltk.core.refactoring.participants.RenameRefactoring;

/**
 * The command to perform rename.
 *
 * @author Valeriy Svydenko
 */
public class RenameCommand {
  private static final Gson GSON = GsonUtils.getInstance();

  /**
   * The command executes Rename refactoring.
   *
   * @param arguments {@link RenameParams} expected
   * @return information about changes
   */
  public static RefactoringResult execute(List<Object> arguments, IProgressMonitor pm) {
    validateArguments(arguments);

    ensureNotCancelled(pm);

    RefactoringResult result = new RefactoringResult();
    CheWorkspaceEdit edit = new CheWorkspaceEdit();
    result.setCheWorkspaceEdit(edit);

    RenameSettings renameSettings =
        GSON.fromJson(GSON.toJson(arguments.get(0)), RenameSettings.class);
    RenameParams params = renameSettings.getRenameParams();

    try {
      IJavaElement curr = null;
      Position position = params.getPosition();
      String uri = params.getTextDocument().getUri();
      RenameKind renameType = renameSettings.getRenameKind();

      switch (renameType) {
        case JAVA_ELEMENT:
          curr = JavaModelUtil.getJavaElement(position, uri, pm);
          break;
        case COMPILATION_UNIT:
          curr = JDTUtils.resolveCompilationUnit(uri);
          break;
        case PACKAGE:
          curr = JDTUtils.resolvePackage(uri);
        default:
          break;
      }

      if (curr == null) {
        return result;
      }

      RenameSupport renameSupport =
          RenameSupport.create(curr, params.getNewName(), RenameSupport.UPDATE_REFERENCES);
      RenameRefactoring renameRefactoring = renameSupport.getRenameRefactoring();
      setSettings(renameSettings, renameRefactoring);

      CreateChangeOperation create =
          new CreateChangeOperation(
              new CheckConditionsOperation(
                  renameRefactoring, CheckConditionsOperation.ALL_CONDITIONS),
              RefactoringStatus.FATAL);
      create.run(pm);
      result.setRefactoringStatus(convertRefactoringStatus(create.getConditionCheckingStatus()));
      Change change = create.getChange();
      if (change == null) {
        return result;
      }

      ChangeUtil.convertChanges(change, edit, pm);
      result.setCheWorkspaceEdit(edit);
    } catch (CoreException ex) {
      JavaLanguageServerPlugin.logException(
          "Problem with rename for " + params.getTextDocument().getUri(), ex);
    }

    return result;
  }

  private static void setSettings(RenameSettings settings, RenameRefactoring refactoring) {
    RefactoringProcessor processor = refactoring.getProcessor();
    if (processor instanceof RenamePackageProcessor) {
      ((RenamePackageProcessor) processor).setRenameSubpackages(settings.isUpdateSubpackages());
    }
    IDelegateUpdating delegateUpdating = refactoring.getAdapter(IDelegateUpdating.class);
    if (delegateUpdating != null && delegateUpdating.canEnableDelegateUpdating()) {
      delegateUpdating.setDelegateUpdating(settings.isDelegateUpdating());
      delegateUpdating.setDeprecateDelegates(settings.isDeprecateDelegates());
    }
    IQualifiedNameUpdating nameUpdating = refactoring.getAdapter(IQualifiedNameUpdating.class);
    if (nameUpdating != null && nameUpdating.canEnableQualifiedNameUpdating()) {
      nameUpdating.setUpdateQualifiedNames(settings.isUpdateQualifiedNames());
      if (settings.isUpdateQualifiedNames()) {
        nameUpdating.setFilePatterns(settings.getFilePatterns());
      }
    }

    IReferenceUpdating referenceUpdating = refactoring.getAdapter(IReferenceUpdating.class);
    if (referenceUpdating != null) {
      referenceUpdating.setUpdateReferences(settings.isUpdateReferences());
    }

    ISimilarDeclarationUpdating similarDeclarationUpdating =
        refactoring.getAdapter(ISimilarDeclarationUpdating.class);
    if (similarDeclarationUpdating != null) {
      similarDeclarationUpdating.setUpdateSimilarDeclarations(
          settings.isUpdateSimilarDeclarations());
      if (settings.isUpdateSimilarDeclarations()) {
        similarDeclarationUpdating.setMatchStrategy(settings.getMatchStrategy().getValue());
      }
    }

    ITextUpdating textUpdating = refactoring.getAdapter(ITextUpdating.class);
    if (textUpdating != null && textUpdating.canEnableTextUpdating()) {
      textUpdating.setUpdateTextualMatches(settings.isUpdateTextualMatches());
    }
  }

  private static void validateArguments(List<Object> arguments) {
    Preconditions.checkArgument(
        !arguments.isEmpty(), RenameCommand.class.getName() + " is expected.");
  }
}
