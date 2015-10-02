package de.jamba.hudson.plugin.wsclean;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TopLevelItem;
import hudson.remoting.RequestAbortedException;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.kohsuke.stapler.DataBoundConstructor;

public class PrePostClean extends BuildWrapper {

	public boolean before;
	private boolean behind;

	public boolean isBefore() {
		return before;
	}

	public void setBefore(boolean before) {
		this.before = before;
		this.behind = !before;
	}

	@DataBoundConstructor
	public PrePostClean(boolean before) {
		this.before = before;
		this.behind = !before;
	}

	@SuppressWarnings("rawtypes")
	@Override
	public Environment setUp(AbstractBuild build, Launcher launcher,
			BuildListener listener) throws IOException, InterruptedException {

		// TearDown
		class TearDownImpl extends Environment {

			@Override
			public boolean tearDown(AbstractBuild build, BuildListener listener)
					throws IOException, InterruptedException {
				if (behind) {
					executeOnSlaves(build, listener);
				}
				return super.tearDown(build, listener);
			}

		}

		if (before) {
			executeOnSlaves(build, listener);
		}
		return new TearDownImpl();

	}

	@SuppressWarnings("rawtypes")
	private void executeOnSlaves(AbstractBuild build, BuildListener listener) {
		listener.getLogger().println("run PrePostClean");
		// select actual running label
		String runNode = build.getBuiltOnStr();

		if (runNode.length() == 0) {
			listener.getLogger().println("running on master");
		} else {
			listener.getLogger().println("running on " + runNode);
		}

		AbstractProject project = build.getProject();
		if (project instanceof TopLevelItem) {
			Label assignedLabel = project.getAssignedLabel();
			if (assignedLabel == null) {
				listener.getLogger().println("skipping roaming project.");
				return;
			}
			Set<Node> nodesForLabel = assignedLabel.getNodes();
			if (nodesForLabel != null) {
				for (Node node : nodesForLabel) {
					if (!runNode.equals(node.getNodeName())) {
						String normalizedName = "".equals(node.getNodeName()) ? "master" : node.getNodeName();
						deleteWorkspace(node.getWorkspaceFor((TopLevelItem) project), listener, normalizedName);
					}
				}
			}
		} else {
			// project isn't instance of TopLevelItem and probably doesn't have fixed workspace location
			// let's iterate over build history and wipe out used workspace locations
			HashSet<String> cleanedNodes = new HashSet<String>();
			AbstractBuild previousBuild = build;
			while (previousBuild != null) {
				previousBuild = (AbstractBuild) previousBuild.getPreviousBuild();
				Node node = previousBuild.getBuiltOn();
				String nodeName = node.getNodeName();
				if (!cleanedNodes.contains(nodeName) && !runNode.equals(nodeName)) {
					cleanedNodes.add(nodeName);
					deleteWorkspace(previousBuild.getWorkspace(), listener, nodeName);
				}
			}
		}
	}

	private void deleteWorkspace(FilePath fp, BuildListener listener, String nodeName) {
		listener.getLogger().println("cleaning on " + nodeName);

		if (fp == null) {
			listener.getLogger().println("No workspace found on " + nodeName + ". Node is maybe offline.");
			return;
		}

		try {
			fp.deleteContents();
		} catch (IOException e) {
			listener.getLogger().println("can't delete on node " + nodeName + "\n" + e.getMessage());
			listener.getLogger().print(e);
		} catch (InterruptedException e) {
			listener.getLogger().println("can't delete on node " + nodeName + "\n" + e.getMessage());
			listener.getLogger().print(e);
		} catch (RequestAbortedException e) {
			listener.getLogger().println("can't delete on node " + nodeName + "\n" + e.getMessage());
		}
	}

	@Extension
	public static final class DescriptorImpl extends BuildWrapperDescriptor {
		public DescriptorImpl() {
			super(PrePostClean.class);
		}

		public String getDisplayName() {
			return "Clean up all workspaces of this job in the same slavegroup";
		}

		public boolean isApplicable(AbstractProject<?, ?> item) {
			return true;
		}

	}
}
