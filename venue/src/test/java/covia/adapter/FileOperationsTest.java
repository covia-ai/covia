package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;

class FileOperationsTest {

	private static final AString ENTRIES = Strings.intern("entries");
	private static final AString TREE = Strings.intern("tree");

	@TempDir
	Path tempDir;

	@Test
	void listSkipsEntriesThatDisappearDuringTraversal() throws Exception {
		Files.writeString(tempDir.resolve("kept.txt"), "kept");
		Files.writeString(tempDir.resolve("upload.part"), "partial");

		AMap<AString, ACell> result = result(FileOperations.list(tempDir, child -> {
			if ("upload.part".equals(child.getFileName().toString())) {
				throw new NoSuchFileException(child.toString());
			}
			return Files.readAttributes(child, BasicFileAttributes.class);
		}));

		AVector<ACell> entries = RT.ensureVector(result.get(ENTRIES));
		assertEquals(1, entries.count());
		assertEquals("kept.txt", RT.ensureString(RT.getIn(entries.get(0), "name")).toString());
		assertWarning(result);
	}

	@Test
	void treeSkipsEntriesThatDisappearDuringTraversal() throws Exception {
		Files.writeString(tempDir.resolve("kept.txt"), "kept");
		Files.writeString(tempDir.resolve("upload.part"), "partial");

		AMap<AString, ACell> result = result(FileOperations.tree(tempDir, Maps.empty(), child -> {
			if ("upload.part".equals(child.getFileName().toString())) {
				throw new NoSuchFileException(child.toString());
			}
			return Files.readAttributes(child, BasicFileAttributes.class);
		}));

		String tree = RT.ensureString(result.get(TREE)).toString();
		assertTrue(tree.contains("kept.txt"));
		assertFalse(tree.contains("upload.part"));
		assertWarning(result);
	}

	@Test
	void listStillPropagatesOtherIoFailures() throws Exception {
		Path file = Files.writeString(tempDir.resolve("private.txt"), "private");

		assertThrows(AccessDeniedException.class, () -> FileOperations.list(tempDir, child -> {
			throw new AccessDeniedException(file.toString());
		}));
	}

	@Test
	void stableListHasNoWarning() throws Exception {
		Files.writeString(tempDir.resolve("file.txt"), "content");

		AMap<AString, ACell> result = result(FileOperations.list(tempDir));

		assertNull(result.get(Fields.WARNINGS));
	}

	private static AMap<AString, ACell> result(ACell value) {
		return RT.ensureMap(value);
	}

	private static void assertWarning(AMap<AString, ACell> result) {
		AVector<ACell> warnings = RT.ensureVector(result.get(Fields.WARNINGS));
		assertEquals(1, warnings.count());
		assertTrue(warnings.get(0).toString().contains("not a complete snapshot"));
	}

	@Test
	void extractModeWithoutTheDocumentsModuleNamesIt() throws Exception {
		Path pdf = tempDir.resolve("report.pdf");
		Files.write(pdf, "%PDF-1.4 fake".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
			() -> FileOperations.read(pdf, "extract", null, null, null));
		assertTrue(ex.getMessage().contains("documents module"), ex.getMessage());
		assertTrue(ex.getMessage().contains("covia-documents"), ex.getMessage());
	}
}
