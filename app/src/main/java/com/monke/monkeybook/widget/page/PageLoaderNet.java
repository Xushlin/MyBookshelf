package com.monke.monkeybook.widget.page;

import android.annotation.SuppressLint;
import android.os.Handler;

import com.monke.monkeybook.bean.BookContentBean;
import com.monke.monkeybook.bean.BookShelfBean;
import com.monke.monkeybook.bean.ChapterListBean;
import com.monke.monkeybook.help.BookshelfHelp;
import com.monke.monkeybook.help.DocumentHelper;
import com.monke.monkeybook.model.WebBookModel;
import com.monke.monkeybook.utils.RxUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.Observer;
import io.reactivex.Scheduler;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

import static com.monke.monkeybook.utils.NetworkUtil.isNetWorkAvailable;

/**
 * 网络页面加载器
 */

public class PageLoaderNet extends PageLoader {
    private static final String TAG = "PageLoaderNet";
    private List<String> downloadingChapterList = new ArrayList<>();
    private ExecutorService executorService;
    private Scheduler scheduler;
    private Handler handler = new Handler();

    PageLoaderNet(PageView pageView, BookShelfBean bookShelfBean) {
        super(pageView, bookShelfBean);
        executorService = Executors.newFixedThreadPool(20);
        scheduler = Schedulers.from(executorService);
    }

    @Override
    public void refreshChapterList() {
        if (bookShelfBean.getChapterList().size() > 0) {
            isChapterListPrepare = true;

            // 目录加载完成，执行回调操作。
            if (mPageChangeListener != null) {
                mPageChangeListener.onCategoryFinish(bookShelfBean.getChapterList());
            }

            // 打开章节
            skipToChapter(bookShelfBean.getDurChapter(), bookShelfBean.getDurChapterPage());
        } else {
            WebBookModel.getInstance().getChapterList(bookShelfBean)
                    .compose(RxUtils::toSimpleSingle)
                    .subscribe(new Observer<BookShelfBean>() {
                        @Override
                        public void onSubscribe(Disposable d) {
                            compositeDisposable.add(d);
                        }

                        @Override
                        public void onNext(BookShelfBean bookShelfBean) {
                            isChapterListPrepare = true;

                            // 目录加载完成
                            if (mPageChangeListener != null) {
                                mPageChangeListener.onCategoryFinish(bookShelfBean.getChapterList());
                            }

                            // 加载并显示当前章节
                            skipToChapter(bookShelfBean.getDurChapter(), bookShelfBean.getDurChapterPage());
                        }

                        @Override
                        public void onError(Throwable e) {
                            chapterError(e.getMessage());
                        }

                        @Override
                        public void onComplete() {

                        }
                    });
        }
    }

    @SuppressLint("DefaultLocale")
    private synchronized void loadContent(final int chapterIndex) {
        if (downloadingChapterList.size() >= 20) return;
        if (DownloadingList(listHandle.CHECK, bookShelfBean.getChapterList(chapterIndex).getDurChapterUrl()))
            return;
        if (null != bookShelfBean && bookShelfBean.getChapterList().size() > 0) {
            Observable.create((ObservableOnSubscribe<Integer>) e -> {
                if (shouldRequestChapter(chapterIndex)) {
                    DownloadingList(listHandle.ADD, bookShelfBean.getChapterList(chapterIndex).getDurChapterUrl());
                    e.onNext(chapterIndex);
                }
                e.onComplete();
            })
                    .flatMap(index -> WebBookModel.getInstance().getBookContent(scheduler, bookShelfBean.getChapterList(chapterIndex), bookShelfBean.getBookInfoBean().getName()))
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Observer<BookContentBean>() {
                        @Override
                        public void onSubscribe(Disposable d) {
                            compositeDisposable.add(d);
                            handler.postDelayed(() -> {
                                if (!d.isDisposed() && bookShelfBean != null) {
                                    DownloadingList(listHandle.REMOVE, bookShelfBean.getChapterList(chapterIndex).getDurChapterUrl());
                                    d.dispose();
                                }
                            }, 30 * 1000);
                        }

                        @SuppressLint("DefaultLocale")
                        @Override
                        public void onNext(BookContentBean bookContentBean) {
                            DownloadingList(listHandle.REMOVE, bookContentBean.getDurChapterUrl());
                            finishContent(bookContentBean.getDurChapterIndex());
                        }

                        @Override
                        public void onError(Throwable e) {
                            DownloadingList(listHandle.REMOVE, bookShelfBean.getChapterList(chapterIndex).getDurChapterUrl());
                            if (chapterIndex == mCurChapterPos) {
                                chapterError(e.getMessage());
                            }
                        }

                        @Override
                        public void onComplete() {
                        }
                    });
        }
    }

    /**
     * 编辑下载列表
     */
    private synchronized boolean DownloadingList(listHandle editType, String value) {
        if (editType == listHandle.ADD) {
            downloadingChapterList.add(value);
            return true;
        } else if (editType == listHandle.REMOVE) {
            downloadingChapterList.remove(value);
            return true;
        } else {
            return downloadingChapterList.indexOf(value) != -1;
        }
    }

    /**
     * 章节下载完成
     */
    private void finishContent(int chapterIndex) {
        if (chapterIndex == mCurChapterPos) {
            super.parseCurChapter();
        }
        if (chapterIndex == mCurChapterPos - 1) {
            super.parsePrevChapter();
        }
        if (chapterIndex == mCurChapterPos + 1) {
            super.parseNextChapter();
        }
    }

    @Override
    protected String getChapterContent(ChapterListBean chapter) throws Exception {
        @SuppressLint("DefaultLocale")
        File file = BookshelfHelp.getBookFile(BookshelfHelp.getCachePathName(bookShelfBean.getBookInfoBean()),
                chapter.getDurChapterIndex(), chapter.getDurChapterName());
        if (!file.exists()) return null;

        byte[] contentByte = DocumentHelper.getBytes(file);
        return new String(contentByte, "UTF-8");
    }

    @SuppressLint("DefaultLocale")
    @Override
    protected boolean noChapterData(ChapterListBean chapter) {
        return !BookshelfHelp.isChapterCached(BookshelfHelp.getCachePathName(bookShelfBean.getBookInfoBean()),
                chapter.getDurChapterIndex(), chapter.getDurChapterName());
    }

    private boolean shouldRequestChapter(Integer chapterIndex) {
        return isNetWorkAvailable() && noChapterData(bookShelfBean.getChapterList(chapterIndex));
    }

    // 装载上一章节的内容
    @Override
    void parsePrevChapter() {
        if (mPageChangeListener != null && mCurChapterPos >= 1) {
            loadContent(mCurChapterPos - 1);
        }
        super.parsePrevChapter();
    }

    // 装载当前章内容。
    @Override
    void parseCurChapter() {
        for (int i = mCurChapterPos; i < Math.min(mCurChapterPos + 5, bookShelfBean.getChapterListSize()); i++) {
            loadContent(i);
        }
        super.parseCurChapter();
    }

    // 装载下一章节的内容
    @Override
    void parseNextChapter() {
        for (int i = mCurChapterPos; i < Math.min(mCurChapterPos + 5, bookShelfBean.getChapterListSize()); i++) {
            loadContent(i);
        }
        super.parseNextChapter();
    }

    @Override
    public void updateChapter() {
        mPageView.getActivity().toast("目录更新中");
        WebBookModel.getInstance().getChapterList(bookShelfBean)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<BookShelfBean>() {
                    @Override
                    public void onSubscribe(Disposable d) {
                        compositeDisposable.add(d);
                    }

                    @Override
                    public void onNext(BookShelfBean bookShelfBean) {
                        isChapterListPrepare = true;

                        if (bookShelfBean.getChapterList().size() > bookShelfBean.getChapterList().size()) {
                            mPageView.getActivity().toast("更新完成,有新章节");
                        } else {
                            mPageView.getActivity().toast("更新完成,无新章节");
                        }

                        // 目录加载完成
                        if (mPageChangeListener != null) {
                            mPageChangeListener.onCategoryFinish(bookShelfBean.getChapterList());
                        }

                        // 加载并显示当前章节
                        skipToChapter(bookShelfBean.getDurChapter(), bookShelfBean.getDurChapterPage());
                    }

                    @Override
                    public void onError(Throwable e) {
                        chapterError(e.getMessage());
                    }

                    @Override
                    public void onComplete() {

                    }
                });
    }

    @Override
    public void closeBook() {
        super.closeBook();
        executorService.shutdown();
    }

    public enum listHandle {
        ADD, REMOVE, CHECK
    }
}
