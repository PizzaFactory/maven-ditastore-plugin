package jp.pizzafactory.ditastore.plugin;

/*
 * Copyright (C) 2015 PizzaFactory Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import static org.twdata.maven.mojoexecutor.MojoExecutor.configuration;
import static org.twdata.maven.mojoexecutor.MojoExecutor.element;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment;
import static org.twdata.maven.mojoexecutor.MojoExecutor.goal;
import static org.twdata.maven.mojoexecutor.MojoExecutor.plugin;

import java.io.File;
import java.io.IOException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.twdata.maven.mojoexecutor.MojoExecutor.Element;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Pull topics in the m2 repository.
 */
@Mojo(name = "pull-topics", threadSafe = true, defaultPhase = LifecyclePhase.PROCESS_RESOURCES)
public class DitamapMojo extends AbstractMojo {
	/**
	 * Location of the file.
	 */
	@Parameter(required = true, defaultValue = "${project.build.directory}")
	private File outputDirectory;

	/**
	 * Location of the DitaMap file.
	 */
	@Parameter(required = true)
	private File ditaMapFile;

	/**
	 * Location of the collected DITA topics.
	 */
	@Parameter(defaultValue = "${project.build.directory}/dita-topics")
	private File ditaTopicsDir;

	/**
	 * Overwrites content if true.
	 */
	@Parameter(defaultValue = "true")
	private boolean isOverwrite;

	@Component
	private MavenProject mavenProject;

	@Component
	private MavenSession mavenSession;

	@Component
	private BuildPluginManager pluginManager;

	/**
	 * Gets the DOM of ditamap
	 * 
	 * @return the DOM that contains the ditamap specified by pom.xml.
	 * @throws MojoExecutionException
	 */
	private Document getDitaMapDocument() throws MojoExecutionException {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		DocumentBuilder builder;
		try {
			builder = factory.newDocumentBuilder();
		} catch (ParserConfigurationException e) {
			throw new MojoExecutionException(
					"Can't create new document builder.", e);
		}

		try {
			return builder.parse(ditaMapFile);
		} catch (SAXException | IOException e) {
			throw new MojoExecutionException("Can't parse the ditamap file.", e);
		}
	}

	/**
	 * Gets &lt;topcref/&gt; in the ditamap.
	 * 
	 * @param document
	 *            the DOM of the ditamap
	 * @return
	 * @throws MojoExecutionException
	 */
	private NodeList getTopicrefs(Document document)
			throws MojoExecutionException {
		XPathFactory xPathFactory = XPathFactory.newInstance();
		XPath xpath = xPathFactory.newXPath();

		try {
			return (NodeList) xpath.evaluate("//topicref", document,
					XPathConstants.NODESET);
		} catch (XPathExpressionException e) {
			throw new MojoExecutionException(
					"Failed to get topcref@href in the ditamap", e);
		}
	}

	private void resolveMapDependency(Node node) throws MojoExecutionException {
		final String locationPath = node.getNodeValue();
		final String[] locationPaths = locationPath.split(":");
		assert ("m2".equals(locationPaths[0]));

		final Element groupId = element("groupId", locationPaths[1]);
		final Element artifactId = element("artifactId", locationPaths[2]);
		final Element version = element("version", locationPaths[3]);
		final String classifier;
		final String type;
		final String path;
		if (locationPaths.length < 5) {
			classifier = locationPaths[4];
			if (locationPaths.length >= 6) {
				type = locationPaths[5];
				if (locationPaths.length >= 7) {
					path = locationPaths[7];
				} else {
					path = null;
				}
			} else {
				type = "";
				path = null;
			}
		} else {
			classifier = "";
			type = "";
			path = null;
		}

		final Plugin dependencyPlugin = plugin("org.apache.maven.plugins",
				"maven-dependency-plugin", "2.0");
		final Element artifactItem = element("artifactItem", groupId,
				artifactId, version, element("classifier", classifier),
				element("type", type));
		final Element overwrite = element("overwrite",
				Boolean.toString(isOverwrite));
		final File resultFile;
		if ("dita".equals(type)) {
			assert path == null;

			File destDir = new File(ditaTopicsDir, "groupId");
			if (!destDir.exists()) {
				destDir.mkdirs();
			}

			final String destFileName = locationPaths[2] + "." + type;
			executeMojo(
					dependencyPlugin,
					goal("copy"),
					configuration(
							element("artifactItems", artifactItem),
							element("outputDirectory",
									destDir.getAbsolutePath()),
							element("destFileName", destFileName), overwrite),
					executionEnvironment(mavenProject, mavenSession,
							pluginManager));

			resultFile = new File(destDir, destFileName);
		} else {
			assert path != null;

			executeMojo(
					dependencyPlugin,
					goal("unpack"),
					configuration(
							element("artifactItems", artifactItem),
							element("outputDirectory",
									ditaTopicsDir.getAbsolutePath()), overwrite),
					executionEnvironment(mavenProject, mavenSession,
							pluginManager));

			resultFile = new File(ditaTopicsDir, path);
		}

		if (!resultFile.exists()) {
			throw new MojoExecutionException("File not found: ["
					+ resultFile.getAbsolutePath() + "[ for [" + locationPath
					+ "].");
		}

		node.setNodeValue(resultFile.getAbsolutePath());
	}

	private void resolveHrefs(Document document) throws MojoExecutionException {
		NodeList nodeList = getTopicrefs(document);
		for (int i = 0; i < nodeList.getLength(); i++) {
			Node node = nodeList.item(i).getAttributes().getNamedItem("href");
			String href = node.getNodeValue();
			if ("m2:".startsWith(href)) {
				resolveMapDependency(node);
			}
		}
	}

	private File prepareOutputDitamap() {
		if (!outputDirectory.exists()) {
			outputDirectory.mkdirs();
		}

		return new File(outputDirectory, ditaMapFile.getName());
	}

	private void outputDitaMap(Document document) throws MojoExecutionException {
		File outputDitamapFile = prepareOutputDitamap();

		try {
			TransformerFactory transFactory = TransformerFactory.newInstance();
			Transformer transformer = transFactory.newTransformer();
			transformer.transform(new DOMSource(document), new StreamResult(
					outputDitamapFile));
		} catch (TransformerException e) {
			throw new MojoExecutionException(
					"Failed to output the converted ditamap.", e);
		}
	}

	@Override
	public void execute() throws MojoExecutionException {
		Document document = getDitaMapDocument();
		resolveHrefs(document);
		outputDitaMap(document);
	}
}
