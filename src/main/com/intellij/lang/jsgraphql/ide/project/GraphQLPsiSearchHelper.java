/**
 * Copyright (c) 2015-present, Jim Kynde Meyer
 * All rights reserved.
 * <p>
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.intellij.lang.jsgraphql.ide.project;


import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.lang.jsgraphql.GraphQLLanguage;
import com.intellij.lang.jsgraphql.ide.injection.GraphQLInjectionIndex;
import com.intellij.lang.jsgraphql.ide.injection.GraphQLLanguageInjectionUtil;
import com.intellij.lang.jsgraphql.ide.references.GraphQLFindUsagesUtil;
import com.intellij.lang.jsgraphql.psi.GraphQLElementTypes;
import com.intellij.lang.jsgraphql.psi.GraphQLFragmentDefinition;
import com.intellij.lang.jsgraphql.psi.GraphQLIdentifier;
import com.intellij.lang.jsgraphql.psi.GraphQLNamedElement;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.AnyPsiChangeListener;
import com.intellij.psi.impl.PsiManagerImpl;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.GlobalSearchScopesCore;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.UsageSearchContext;
import com.intellij.psi.search.scope.packageSet.NamedScope;
import com.intellij.psi.search.scope.packageSet.NamedScopesHolder;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.indexing.FileBasedIndex;
import org.apache.commons.compress.utils.IOUtils;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Enables cross-file searches for PSI references
 */
public class GraphQLPsiSearchHelper {

    public static final Key<Boolean> IS_GRAPHQL_BUILT_IN_SCHEMA_VIRTUAL_FILE = Key.create("JSGraphQL.built-in.schema.virtual-file");
    public static final Key<PsiFile> GRAPHQL_BUILT_IN_SCHEMA_PSI_FILE = Key.create("JSGraphQL.build-in.schema.psi-file");

    private final static Logger log = Logger.getInstance(GraphQLPsiSearchHelper.class);

    private static final FileType[] FILE_TYPES = GraphQLFindUsagesUtil.INCLUDED_FILE_TYPES.toArray(new FileType[GraphQLFindUsagesUtil.INCLUDED_FILE_TYPES.size()]);

    private final Project myProject;
    private PluginDescriptor pluginDescriptor;
    private final Map<String, GraphQLFragmentDefinition> fragmentDefinitionsByName = Maps.newConcurrentMap();
    private final GlobalSearchScope searchScope;
    private final GlobalSearchScope builtInSchemaScope;

    public static GraphQLPsiSearchHelper getService(@NotNull Project project) {
        return ServiceManager.getService(project, GraphQLPsiSearchHelper.class);
    }


    public GraphQLPsiSearchHelper(@NotNull final Project project) {
        myProject = project;
        pluginDescriptor = PluginManager.getPlugin(PluginId.getId("com.intellij.lang.jsgraphql"));
        builtInSchemaScope = GlobalSearchScope.fileScope(project, getBuiltInSchema().getVirtualFile());
        searchScope = GlobalSearchScope.getScopeRestrictedByFileTypes(GlobalSearchScope.projectScope(myProject), FILE_TYPES).union(builtInSchemaScope);
        project.getMessageBus().connect().subscribe(PsiManagerImpl.ANY_PSI_CHANGE_TOPIC, new AnyPsiChangeListener.Adapter() {
            @Override
            public void beforePsiChanged(boolean isPhysical) {
                // clear the cache on each PSI change
                fragmentDefinitionsByName.clear();
            }
        });
    }

    /**
     * Uses custom editable scopes to limit the schema and reference resolution of a GraphQL psi element
     */
    public GlobalSearchScope getSchemaScope(PsiElement element) {
        final NamedScopesHolder[] holders = NamedScopesHolder.getAllNamedScopeHolders(myProject);
        for (NamedScopesHolder holder : holders) {
            final NamedScope[] scopes = holder.getEditableScopes();  // don't need predefined scopes as we default to entire project
            for (NamedScope scope : scopes) {
                final GlobalSearchScope filterSearchScope = GlobalSearchScopesCore.filterScope(myProject, scope);
                VirtualFile virtualFile = element.getContainingFile().getVirtualFile();
                if (virtualFile == null) {
                    // in memory PsiFile such as the completion PSI
                    virtualFile = element.getContainingFile().getOriginalFile().getVirtualFile();
                }
                if (virtualFile != null) {
                    if (filterSearchScope.contains(virtualFile)) {
                        // TODO JKM cache based on virtual file system
                        // Scope should be combination of completion PSI and original??
                        return searchScope.intersectWith(filterSearchScope.union(builtInSchemaScope));
                    }
                } else {
                    // TODO JKM
                    System.err.println("Unable to resolve scope for " + element);
                }
            }
        }
        // default is entire project limited by relevant file types
        return searchScope;
    }

    /**
     * Provides a search scope that indicates from where usages can occur for the specified element.
     * The main use case is for injected GraphQL where Idea defaults to the current file only.
     */
    public GlobalSearchScope getUseScope(PsiElement element) {
        if (element.getContainingFile().getVirtualFile() != null) {
            return getSchemaScope(element);
        } else {
            return searchScope;
        }
    }

    /**
     * Finds all fragment definition across files in the project
     *
     * @param scopedElement the starting point for finding known fragment definitions
     * @return a list of known fragment definitions, or an empty list if the index is not yet ready
     */
    public List<GraphQLFragmentDefinition> getKnownFragmentDefinitions(PsiElement scopedElement) {
        try {
            final List<GraphQLFragmentDefinition> fragmentDefinitions = Lists.newArrayList();
            PsiSearchHelper.SERVICE.getInstance(myProject).processElementsWithWord((psiElement, offsetInElement) -> {
                if (psiElement.getNode().getElementType() == GraphQLElementTypes.FRAGMENT_KEYWORD) {
                    final GraphQLFragmentDefinition fragmentDefinition = PsiTreeUtil.getParentOfType(psiElement, GraphQLFragmentDefinition.class);
                    if (fragmentDefinition != null && fragmentDefinition.getNameIdentifier() != null) {
                        fragmentDefinitions.add(fragmentDefinition);
                    }
                }
                return true;
            }, getSchemaScope(scopedElement), "fragment", UsageSearchContext.IN_CODE, true, true);
            return fragmentDefinitions;
        } catch (IndexNotReadyException e) {
            // can't search yet (e.g. during project startup)
        }
        return Collections.emptyList();
    }

    /**
     * Gets a resolved reference or null if no reference or resolved element is found
     *
     * @param psiElement the element to get a resolved reference for
     * @return the resolved reference, or null if non is available
     */
    public static GraphQLIdentifier getResolvedReference(GraphQLNamedElement psiElement) {
        if (psiElement != null) {
            final PsiElement nameIdentifier = psiElement.getNameIdentifier();
            if (nameIdentifier != null) {
                PsiReference reference = nameIdentifier.getReference();
                if (reference != null) {
                    PsiElement resolved = reference.resolve();
                    return resolved instanceof GraphQLIdentifier ? (GraphQLIdentifier) resolved : null;
                }
            }
        }
        return null;
    }

    /**
     * Processes all named elements that match the specified word, e.g. the declaration of a type name
     */
    public void processElementsWithWord(PsiElement scopedElement, String word, Predicate<PsiNamedElement> predicate) {
        try {
            PsiSearchHelper.SERVICE.getInstance(myProject).processElementsWithWord((psiElement, offsetInElement) -> {
                if (psiElement instanceof PsiNamedElement) {
                    return predicate.test((PsiNamedElement) psiElement);
                }
                return true;
            }, getSchemaScope(scopedElement), word, UsageSearchContext.IN_CODE, true, true);

            // also include the built-in schema
            getBuiltInSchema().accept(new PsiRecursiveElementVisitor() {
                @Override
                public void visitElement(PsiElement element) {
                    if (element instanceof PsiNamedElement && word.equals(element.getText())) {
                        predicate.test((PsiNamedElement) element);
                    }
                    super.visitElement(element);
                }
            });

        } catch (IndexNotReadyException e) {
            // can't search yet (e.g. during project startup)
        }
    }

    /**
     * Gets the build-in Schema that all endpoints support, including the introspection types, fields, directives and default scalars.
     */
    public PsiFile getBuiltInSchema() {
        PsiFile psiFile = myProject.getUserData(GRAPHQL_BUILT_IN_SCHEMA_PSI_FILE);
        if (psiFile == null) {
            final PsiFileFactory psiFileFactory = PsiFileFactory.getInstance(myProject);
            String specSchemaText = "";
            try {
                try (InputStream inputStream = pluginDescriptor.getPluginClassLoader().getResourceAsStream("/META-INF/graphql specification schema.graphql")) {
                    if (inputStream != null) {
                        specSchemaText = new String(IOUtils.toByteArray(inputStream));
                    }
                }
            } catch (IOException e) {
                log.error("Unable to load spec schema", e);
                Notifications.Bus.notify(new Notification("GraphQL", "Unable to load GraphQL Specification Schema", e.getMessage(), NotificationType.ERROR));
            }
            psiFile = psiFileFactory.createFileFromText("GraphQL Specification Schema", GraphQLLanguage.INSTANCE, specSchemaText);
            myProject.putUserData(GRAPHQL_BUILT_IN_SCHEMA_PSI_FILE, psiFile);
            psiFile.getVirtualFile().putUserData(IS_GRAPHQL_BUILT_IN_SCHEMA_VIRTUAL_FILE, true);
        }
        return psiFile;
    }

    /**
     * Uses the {@link GraphQLInjectionIndex} to process injected GraphQL PsiFiles
     *
     * @param scopedElement the starting point of the enumeration settings the scopedElement of the processing
     * @param consumer      a consumer that will be invoked for each injected GraphQL PsiFile
     */
    public void processInjectedGraphQLPsiFiles(PsiElement scopedElement, Consumer<PsiFile> consumer) {
        final PsiManager psiManager = PsiManager.getInstance(scopedElement.getProject());
        final InjectedLanguageManager injectedLanguageManager = InjectedLanguageManager.getInstance(scopedElement.getProject());
        FileBasedIndex.getInstance().getFilesWithKey(GraphQLInjectionIndex.NAME, Collections.singleton(GraphQLInjectionIndex.DATA_KEY), virtualFile -> {
            final PsiFile fileWithInjection = psiManager.findFile(virtualFile);
            if (fileWithInjection != null) {
                fileWithInjection.accept(new PsiRecursiveElementVisitor() {
                    @Override
                    public void visitElement(PsiElement element) {
                        if (GraphQLLanguageInjectionUtil.isJSGraphQLLanguageInjectionTarget(element)) {
                            injectedLanguageManager.enumerate(element, (injectedPsi, places) -> {
                                consumer.accept(injectedPsi);
                            });
                        } else {
                            // visit deeper until injection found
                            super.visitElement(element);
                        }
                    }
                });
            }
            return true;
        }, getSchemaScope(scopedElement));

    }

    /**
     * Gets the virtual file system path of a PSI file
     */
    public static String getFileName(PsiFile psiFile) {
        VirtualFile virtualFile = psiFile.getVirtualFile();
        if(virtualFile == null) {
            virtualFile = psiFile.getOriginalFile().getVirtualFile();
        }
        if(virtualFile != null) {
            return virtualFile.getPath();
        }
        return psiFile.getName();
    }

}