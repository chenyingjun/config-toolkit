package com.dangdang.config.service.file;

import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watcher for file changes
 * 
 * @author <a href="mailto:wangyuxuan@dangdang.com">Yuxuan Wang</a>
 *
 */
public class FileChangeEventListener implements Runnable {

	private WatchService watcher;

	private FileConfigGroup configGroup;

	private Path watchedFile;

	public FileChangeEventListener(WatchService watcher, FileConfigGroup configGroup, Path watchedFile) {
		super();
		this.watcher = watcher;
		this.configGroup = configGroup;
		this.watchedFile = watchedFile;
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(FileChangeEventListener.class);
//https://blog.csdn.net/lirx_tech/article/details/51425364#commentBox
	@Override
	public void run() {
		while (true) {
			// wait for key to be signaled
			WatchKey key;
			try {
				//尝试获取下一个变化信息的监控池，如果没有变化则一直等待
				key = watcher.take();
			} catch (InterruptedException x) {
				return;
			}

			//一个文件变化动作可能会引发一系列的事件，因此WatchKey中保存着一个事件列表List<WatchEvent<?>> list，可以通过WatchKey的pollEvents方法获得该列表
			for (WatchEvent<?> event : key.pollEvents()) {
				//返回事件类型（ENTRY_CREATE、ENTRY_DELETE、ENTRY_MODIFY之一）
				WatchEvent.Kind<?> kind = event.kind();

				// This key is registered only for ENTRY_MODIFY events,
				if (kind != StandardWatchEventKinds.ENTRY_MODIFY) {
					continue;
				}

				// The filename is the context of the event.
				@SuppressWarnings("unchecked")
				WatchEvent<Path> ev = (WatchEvent<Path>) event;
				Path filename = ev.context();

				LOGGER.debug("File {} changed.", filename);

				if (isSameFile(filename, watchedFile)) {
					configGroup.initConfigs();
				}

			}
			//完成一次监控就需要重置监控器一次
			//因为当你使用poll或take时监控器线程就被阻塞了，因为你处理文件变化的操作可能需要挺长时间的，为了防止在这段时间内又要处理其他类似的事件，因此需要阻塞监控器线程，而调用reset表示重启该线程
			boolean status = key.reset();
			if(!status) {
				break;
			}
		}
	}

	private boolean isSameFile(Path file1, Path file2) {
		return file1.getFileName().equals(file2.getFileName());
	}

}
