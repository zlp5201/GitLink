package uk.co.ben_gibson.git.link;

import com.intellij.ide.browsers.BrowserLauncher;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.co.ben_gibson.git.link.Exception.GitLinkException;
import uk.co.ben_gibson.git.link.Git.Branch;
import uk.co.ben_gibson.git.link.Git.Commit;
import uk.co.ben_gibson.git.link.Git.Repository;
import uk.co.ben_gibson.git.link.Git.RepositoryFactory;
import uk.co.ben_gibson.git.link.UI.ExceptionRenderer;
import uk.co.ben_gibson.git.link.UI.LineSelection;
import uk.co.ben_gibson.git.link.Url.Factory.*;
import uk.co.ben_gibson.git.link.Url.Modifier.UrlModifier;
import uk.co.ben_gibson.git.link.Url.Modifier.UrlModifierProvider;

import java.net.URISyntaxException;
import java.net.URL;
import java.util.Objects;

public class GitLink
{
    private Logger              logger = Logger.getInstance(ServiceManager.getService(Plugin.class).displayName());
    private RepositoryFactory   repositoryFactory;
    private ExceptionRenderer   exceptionRenderer;
    private UrlModifierProvider urlModifierProvider;


    GitLink(
        RepositoryFactory repositoryFactory,
        ExceptionRenderer exceptionRenderer,
        UrlModifierProvider urlModifierProvider
    )
    {
        this.repositoryFactory = repositoryFactory;
        this.exceptionRenderer = exceptionRenderer;
        this.urlModifierProvider = urlModifierProvider;
    }


    public void openFile(
        @NotNull Project project,
        @NotNull VirtualFile file,
        @Nullable Commit commit,
        @Nullable LineSelection lineSelection
    )
    {
        try {
            Preferences preferences   = Preferences.getInstance(project);
            Repository  repository    = this.repositoryFactory.create(project, file, preferences.getDefaultBranch(), preferences.remoteName);
            UrlFactory  urlFactory    = UrlFactoryProvider.fromPreferences(preferences).urlFactoryForHost(preferences.getRemoteHost());

            Task.Backgroundable task = new Task.Backgroundable(project, "GitLink - Opening file")
            {
                @Override
                public void run(@NotNull ProgressIndicator indicator)
                {
                    try {

                        URL url;

                        // If we have explicitly been given a commit or the current commit exists on the remote
                        // repository then open the file at that commit otherwise use the branch.
                        if (commit != null || repository.isCurrentCommitOnRemote()) {
                            url = urlFactory.createUrlToFileAtCommit(
                                repository.remote(),
                                repository.repositoryFileFromVirtualFile(file),
                                Objects.requireNonNull((commit != null) ? commit : repository.currentCommit()),
                                lineSelection
                            );
                        } else {
                            url = urlFactory.createUrlToFileOnBranch(
                                repository.remote(),
                                repository.repositoryFileFromVirtualFile(file),
                                repository.currentBranch(),
                                lineSelection
                            );
                        }

                        GitLink.this.openUrlInBrowser(preferences, url);

                    } catch (GitLinkException e) {
                        GitLink.this.exceptionRenderer.render(e);
                    }
                }
            };

            task.queue();

        } catch (GitLinkException e) {
            e.printStackTrace();
            this.exceptionRenderer.render(e);
        }
    }


    public void openCommit(@NotNull Project project, @NotNull Commit commit, @NotNull VirtualFile file)
    {
        try {
            Preferences preferences   = Preferences.getInstance(project);
            Branch      defaultBranch = new Branch(preferences.defaultBranchName);
            Repository  repository    = this.repositoryFactory.create(project, file, defaultBranch, preferences.remoteName);
            UrlFactory  urlFactory    = UrlFactoryProvider.fromPreferences(preferences).urlFactoryForHost(preferences.getRemoteHost());

            Task.Backgroundable task = new Task.Backgroundable(project, "GitLink - Opening commit")
            {
                @Override
                public void run(@NotNull ProgressIndicator indicator)
                {
                    try {

                        URL url = urlFactory.createUrlToCommit(repository.remote(), commit);

                        GitLink.this.openUrlInBrowser(preferences, url);

                    } catch (GitLinkException e) {
                        GitLink.this.exceptionRenderer.render(e);
                    }
                }
            };

            task.queue();

        } catch (GitLinkException e) {
            this.exceptionRenderer.render(e);
        }
    }


    private void openUrlInBrowser(@NotNull Preferences preferences, @NotNull URL url) throws GitLinkException
    {
        GitLink.this.logger.info(String.format("Generated URL '%s'", url.toString()));

        for (UrlModifier urlModifier : GitLink.this.urlModifierProvider.modifiers()) {
            if (preferences.isModifierEnabled(urlModifier)) {
                url = urlModifier.modify(url);
                GitLink.this.logger.info(String.format(
                    "Applied modifier '%s' - '%s'",
                    urlModifier.name(),
                    url.toString()
                ));
            }
        }

        try {
            BrowserLauncher.getInstance().open(url.toURI().toASCIIString());
        } catch (URISyntaxException e) {
            throw new GitLinkException(e.getMessage());
        }
    }
}
