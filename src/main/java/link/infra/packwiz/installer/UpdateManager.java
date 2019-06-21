package link.infra.packwiz.installer;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import com.moandjiezana.toml.Toml;

import link.infra.packwiz.installer.metadata.IndexFile;
import link.infra.packwiz.installer.metadata.ManifestFile;
import link.infra.packwiz.installer.metadata.PackFile;
import link.infra.packwiz.installer.metadata.hash.GeneralHashingSource;
import link.infra.packwiz.installer.metadata.hash.HashUtils;
import link.infra.packwiz.installer.request.HandlerManager;
import link.infra.packwiz.installer.ui.IUserInterface;
import link.infra.packwiz.installer.ui.InstallProgress;
import okio.Buffer;
import okio.Okio;
import okio.Source;

public class UpdateManager {

	public final Options opts;
	public final IUserInterface ui;

	public static class Options {
		public URI downloadURI = null;
		public String manifestFile = "packwiz.json"; // TODO: make configurable
		public String packFolder = ".";
		public Side side = Side.CLIENT;

		public static enum Side {
			@SerializedName("client")
			CLIENT("client"), @SerializedName("server")
			SERVER("server"), @SerializedName("both")
			BOTH("both", new Side[] { CLIENT, SERVER });

			private final String sideName;
			private final Side[] depSides;

			Side(String sideName) {
				this.sideName = sideName.toLowerCase();
				this.depSides = null;
			}

			Side(String sideName, Side[] depSides) {
				this.sideName = sideName.toLowerCase();
				this.depSides = depSides;
			}

			@Override
			public String toString() {
				return this.sideName;
			}

			public boolean hasSide(Side tSide) {
				if (this.equals(tSide)) {
					return true;
				}
				if (this.depSides != null) {
					for (int i = 0; i < this.depSides.length; i++) {
						if (this.depSides[i].equals(tSide)) {
							return true;
						}
					}
				}
				return false;
			}

			public static Side from(String name) {
				String lowerName = name.toLowerCase();
				for (Side side : Side.values()) {
					if (side.sideName == lowerName) {
						return side;
					}
				}
				return null;
			}
		}
	}

	public UpdateManager(Options opts, IUserInterface ui) {
		this.opts = opts;
		this.ui = ui;
		this.start();
	}

	protected void start() {
		this.checkOptions();

		ui.submitProgress(new InstallProgress("Loading manifest file..."));
		Gson gson = new Gson();
		ManifestFile manifest;
		try {
			manifest = gson.fromJson(new FileReader(Paths.get(opts.packFolder, opts.manifestFile).toString()),
					ManifestFile.class);
		} catch (FileNotFoundException e) {
			manifest = new ManifestFile();
		} catch (JsonSyntaxException | JsonIOException e) {
			ui.handleExceptionAndExit(e);
			return;
		}

		ui.submitProgress(new InstallProgress("Loading pack file..."));
		GeneralHashingSource packFileSource;
		try {
			Source src = HandlerManager.getFileSource(opts.downloadURI);
			packFileSource = HashUtils.getHasher("sha256").getHashingSource(src);
		} catch (Exception e) {
			// TODO: still launch the game if updating doesn't work?
			// TODO: ask user if they want to launch the game, exit(1) if they don't
			ui.handleExceptionAndExit(e);
			return;
		}
		PackFile pf;
		try {
			pf = new Toml().read(Okio.buffer(packFileSource).inputStream()).to(PackFile.class);
		} catch (IllegalStateException e) {
			ui.handleExceptionAndExit(e);
			return;
		}

		if (packFileSource.hashIsEqual(manifest.packFileHash)) {
			System.out.println("Hash already up to date!");
			// WOOO it's already up to date
			// todo: --force?
		}

		System.out.println(pf.name);

		try {
			processIndex(HandlerManager.getNewLoc(opts.downloadURI, pf.index.file),
					HashUtils.getHash(pf.index.hash, pf.index.hashFormat), manifest);
		} catch (Exception e1) {
			ui.handleExceptionAndExit(e1);
		}

		// When successfully updated
		manifest.packFileHash = packFileSource.getHash();
		// update other hashes
		// TODO: don't do this on failure?
		try (Writer writer = new FileWriter(Paths.get(opts.packFolder, opts.manifestFile).toString())) {
			gson.toJson(manifest, writer);
		} catch (IOException e) {
			// TODO: add message?
			ui.handleException(e);
		}

	}

	protected void checkOptions() {
		// TODO: implement
	}

	protected void processIndex(URI indexUri, Object indexHash, ManifestFile manifest) {
		GeneralHashingSource indexFileSource;
		try {
			Source src = HandlerManager.getFileSource(opts.downloadURI);
			indexFileSource = HashUtils.getHasher("sha256").getHashingSource(src);
		} catch (Exception e) {
			// TODO: still launch the game if updating doesn't work?
			// TODO: ask user if they want to launch the game, exit(1) if they don't
			ui.handleExceptionAndExit(e);
			return;
		}
		IndexFile indexFile;
		try {
			indexFile = new Toml().read(Okio.buffer(indexFileSource).inputStream()).to(IndexFile.class);
		} catch (IllegalStateException e) {
			ui.handleExceptionAndExit(e);
			return;
		}

		if (!indexFileSource.hashIsEqual(indexHash)) {
			System.out.println("Hash problems!!!!!!!");
			// TODO: throw exception
		}

		// TODO: progress bar
		ConcurrentLinkedQueue<Exception> exceptionQueue = new ConcurrentLinkedQueue<Exception>();
		List<IndexFile.File> newFiles = indexFile.files.stream().filter(f -> {
			ManifestFile.File cachedFile = manifest.cachedFiles.get(f.file);
			Object newHash;
			try {
				newHash = HashUtils.getHash(f.hashFormat, f.hash);
			} catch (Exception e) {
				exceptionQueue.add(e);
				return false;
			}
			return cachedFile == null || newHash.equals(cachedFile.hash);
		}).parallel().map(f -> {
			try {
				f.downloadMeta(indexFile, indexUri);
			} catch (Exception e) {
				exceptionQueue.add(e);
			}
			return f;
		}).collect(Collectors.toList());

		for (Exception e : exceptionQueue) {
			// TODO: collect all exceptions, present in one dialog
			ui.handleException(e);
		}

		// TODO: present options
		// TODO: all options should be presented, not just new files!!!!!!!
		// and options should be readded to newFiles after option -> true
		newFiles.stream().filter(f -> f.linkedFile != null).filter(f -> f.linkedFile.option != null).map(f -> {
			return "option: " + (f.linkedFile.option.description == null ? "null" : f.linkedFile.option.description);
		}).forEachOrdered(desc -> {
			System.out.println(desc);
		});

		// TODO: different thread pool type?
		ExecutorService threadPool = Executors.newFixedThreadPool(10);
		CompletionService<DownloadCompletion> completionService = new ExecutorCompletionService<DownloadCompletion>(
				threadPool);

		for (IndexFile.File f : newFiles) {
			ManifestFile.File cachedFile = manifest.cachedFiles.get(f.file);
			completionService.submit(new Callable<DownloadCompletion>() {
				public DownloadCompletion call() {
					DownloadCompletion dc = new DownloadCompletion();
					dc.file = f;

					if (cachedFile.linkedFileHash != null && f.linkedFile != null) {
						try {
							if (cachedFile.linkedFileHash.equals(f.linkedFile.getHash())) {
								// Do nothing, the file didn't change
								// TODO: but if the hash of the metafile changed, what did change?????
								// should this be checked somehow??
								return dc;
							}
						} catch (Exception e) {}
					}

					try {
						Source src = f.getSource(indexUri);
						GeneralHashingSource fileSource = HashUtils.getHasher(f.hashFormat).getHashingSource(src);
						Buffer data = new Buffer();
						Okio.buffer(fileSource).readAll(data);

						Object hash;
						if (f.linkedFile != null) {
							hash = f.linkedFile.getHash();
						} else {
							hash = f.getHash();
						}
						if (fileSource.hashIsEqual(hash)) {
							Files.copy(data.inputStream(), Paths.get(opts.packFolder, f.getDestURI().toString()), StandardCopyOption.REPLACE_EXISTING);
						} else {
							dc.err = new Exception("Hash invalid!");
						}
						
						return dc;
					} catch (Exception e) {
						dc.err = e;
						return dc;
					}
				}
			});
		}

		for (int i = 0; i < newFiles.size(); i++) {
			DownloadCompletion ret;
			try {
				ret = completionService.take().get();
			} catch (InterruptedException | ExecutionException e) {
				// TODO: collect all exceptions, present in one dialog
				ui.handleException(e);
				ret = null;
			}
			// Update manifest
			if (ret != null && ret.err == null && ret.file != null) {
				ManifestFile.File newCachedFile = new ManifestFile.File();
				try {
					newCachedFile.hash = ret.file.getHash();
					if (newCachedFile.hash == null) {
						throw new Exception("Invalid hash!");
					}
				} catch (Exception e) {
					ret.err = e;
				}
				if (ret.file.metafile && ret.file.linkedFile != null) {
					newCachedFile.isOptional = ret.file.linkedFile.isOptional();
					if (newCachedFile.isOptional) {
						newCachedFile.optionValue = ret.file.optionValue;
					}
					try {
						newCachedFile.linkedFileHash = ret.file.linkedFile.getHash();
					} catch (Exception e) {
						ret.err = e;
					}
				}
			}
			// TODO: show errors properly?
			String progress;
			if (ret != null && ret.file != null) {
				progress = "Downloaded " + ret.file.getName();
			} else if (ret.err != null) {
				progress = "Failed to download: " + ret.err.getMessage();
			} else {
				progress = "Failed to download, unknown reason";
			}
			ui.submitProgress(new InstallProgress(progress, i + 1, newFiles.size()));
		}
		// option = false file hashes should be stored to disk, but not downloaded
		// TODO: don't include optional files in progress????
	}

	private class DownloadCompletion {
		Exception err;
		IndexFile.File file;
	}
}
