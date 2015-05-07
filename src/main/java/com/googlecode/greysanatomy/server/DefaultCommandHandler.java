package com.googlecode.greysanatomy.server;

import com.googlecode.greysanatomy.command.Command;
import com.googlecode.greysanatomy.command.Commands;
import com.googlecode.greysanatomy.command.QuitCommand;
import com.googlecode.greysanatomy.command.ShutdownCommand;
import com.googlecode.greysanatomy.exception.CommandException;
import com.googlecode.greysanatomy.exception.CommandInitializationException;
import com.googlecode.greysanatomy.exception.CommandNotFoundException;
import com.googlecode.greysanatomy.exception.GaExecuteException;
import com.googlecode.greysanatomy.probe.ProbeJobs;
import com.googlecode.greysanatomy.util.GaStringUtils;
import com.googlecode.greysanatomy.util.LogUtils;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.instrument.Instrumentation;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.String.format;

/**
 * 命令处理器
 * Created by vlinux on 15/5/2.
 */
public class DefaultCommandHandler implements CommandHandler {


    private final Logger logger = LogUtils.getLogger();

    private static final int BUFFER_SIZE = 4 * 1024;

    private final GaServer gaServer;
    private final Instrumentation instrumentation;

    public DefaultCommandHandler(GaServer gaServer, Instrumentation instrumentation) {
        this.gaServer = gaServer;
        this.instrumentation = instrumentation;
    }

    @Override
    public void executeCommand(final String line, final GaSession gaSession) throws IOException {

        final SocketChannel socketChannel = gaSession.getSocketChannel();

        // 只有没有任务在后台运行的时候才能接受服务端响应
        if (gaSession.hasJobRunning()) {
            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, format("session[%d] has running job[%s] ignore this command.",
                        gaSession.getSessionId(),
                        gaSession.getCurrentJobId()));
            }
            return;
        }

        // 只有输入了有效字符才进行命令解析
        // 否则仅仅重绘提示符
        if (GaStringUtils.isBlank(line)) {

            // 这里因为控制不好，造成了输出两次提示符的问题
            // 第一次是因为这里，第二次则是下边（命令结束重绘提示符）
            // 这里做了一次取巧，虽然依旧是重绘了两次提示符，但在提示符之间增加了\r
            // 这样两次重绘都是在同一个位置，这样就没有人能发现，其实他们是被绘制了两次
            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, "reDrawPrompt for blank line.");
            }

            reDrawPrompt(socketChannel, gaSession.getCharset());
            return;
        }

        try {
            final Command command = Commands.getInstance().newCommand(line);
            execute(gaSession, command);

            // 退出命令，需要关闭Socket
            if (command instanceof QuitCommand) {
                gaSession.destroy();
            }

            // 关闭命令，需要关闭整个服务端
            else if (command instanceof ShutdownCommand) {
                DefaultCommandHandler.this.gaServer.unbind();
            }

            // 其他命令需要重新绘制提示符
            else {

                if (logger.isLoggable(Level.FINE)) {
                    logger.log(Level.FINE, "reDrawPrompt for command execute finished.");
                }

                reDrawPrompt(socketChannel, gaSession.getCharset());
            }

        }

        // 命令准备错误(参数校验等)
        catch (CommandException t) {

            final String message;
            if (t instanceof CommandNotFoundException) {
                message = format("command \"%s\" not found.\n",
                        t.getCommand());
            } else if (t instanceof CommandInitializationException) {
                message = format("command \"%s\" init failed.\n",
                        t.getCommand());
            } else {
                message = format("command \"%s\" prepare failed : %s\n",
                        t.getCommand(), GaStringUtils.getCauseMessage(t));
            }

            write(socketChannel, message, gaSession.getCharset());
            reDrawPrompt(socketChannel, gaSession.getCharset());
            if (logger.isLoggable(Level.INFO)) {
                logger.log(Level.INFO, message);
            }

            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, message, t);
            }
        }

        // 命令执行错误
        catch (GaExecuteException e) {
            final String message = format("command execute failed, %s\n",
                    GaStringUtils.getCauseMessage(e));
            if (logger.isLoggable(Level.WARNING)) {
                logger.log(Level.WARNING, message, e);
            }
            write(socketChannel, message, gaSession.getCharset());
            reDrawPrompt(socketChannel, gaSession.getCharset());
        }

    }


    /*
     * 执行命令
     */
    private void execute(final GaSession gaSession, final Command command) throws GaExecuteException, IOException {
        final AtomicBoolean isFinishRef = new AtomicBoolean(false);

        final int jobId;
        try {
            jobId = ProbeJobs.createJob();
            // 注入当前会话所执行的jobId，其他地方需要
            gaSession.setCurrentJobId(jobId);
        } catch (IOException e) {
            throw new GaExecuteException(format("crate job failed. sessionId=%s", gaSession.getSessionId()), e);
        }

        final Command.Action action = command.getAction();
        final Command.Info info = new Command.Info(instrumentation, gaSession.getSessionId(), jobId);
        final Command.Sender sender = new Command.Sender() {

            @Override
            public void send(boolean isF, String message) {

                final Writer writer = ProbeJobs.getJobWriter(jobId);
                if (null != writer) {
                    try {

                        if( null != message ) {
                            writer.write(message);
                        }


                        // 这里为了美观，在每个命令输出最后一行的时候换行
                        if (isF) {
                            writer.write("\n");
                        }

                        writer.flush();
                    } catch (IOException e) {
                        if (logger.isLoggable(Level.WARNING)) {
                            logger.log(Level.WARNING, format("command write job failed. sessionId=%d;jobId=%d;",
                                    gaSession.getSessionId(), jobId), e);
                        }

                        // 如果任务写文件失败了，需要立即将写入标记为完成
                        // 让命令执行线程尽快结束，但这种写法非常有疑惑性质
                        // 后期可能没人能理解这里
                        isFinishRef.set(true);
                    }
                }

                if (isF) {
                    isFinishRef.set(true);
                }

            }

        };

        final CharBuffer buffer = CharBuffer.allocate(BUFFER_SIZE);
        try {
            action.action(gaSession, info, sender);
        }

        // 命令执行错误必须纪录
        catch (Throwable t) {
            throw new GaExecuteException(format("execute failed. sessionId=%s", gaSession.getSessionId()), t);
        }

        // 跑任务
        jobRunning(gaSession, isFinishRef, jobId, buffer);


    }

    private void jobRunning(GaSession gaSession, AtomicBoolean isFinishRef, int jobId, CharBuffer buffer) throws IOException, GaExecuteException {
        // 先将会话的写打开
        gaSession.markJobRunning(true);

        try {

            final Thread currentThread = Thread.currentThread();
            try {

                while (!gaSession.isDestroy()
                        && gaSession.hasJobRunning()
                        && !currentThread.isInterrupted()) {

                    final Reader reader = ProbeJobs.getJobReader(jobId);
                    if (null == reader) {
                        break;
                    }

                    // touch the session
                    gaSession.touch();

                    final int readCount;
                    try {
                        readCount = reader.read(buffer);
                    } catch (IOException e) {
                        throw new GaExecuteException("read job message failed.", e);
                    }


                    // 首先将一部分数据读取到buffer中
                    if (-1 == readCount) {

                        // 当读到EOF的时候，同时Sender标记为isFinished
                        // 说明整个命令结束了，标记整个会话为不可写，结束命令循环
                        if (isFinishRef.get()) {
                            gaSession.markJobRunning(false);
                            break;
                        }

                        // 若已经让文件到达EOF，说明读取比写入快，需要休息下
                        // 间隔200ms，人类操作无感知
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            currentThread.interrupt();
                        }

                    }

                    // 读出了点东西
                    else {

                        buffer.flip();
                        final ByteBuffer writeByteBuffer = gaSession.getCharset().encode(buffer);
                        while (writeByteBuffer.hasRemaining()) {

                            if (-1 == gaSession.getSocketChannel().write(writeByteBuffer)) {
                                // socket broken
                                if (logger.isLoggable(Level.INFO)) {
                                    logger.log(Level.INFO, format("network communicate failed, session will be destroy. sessionId=%d;jobId=%d;",
                                            gaSession.getSessionId(), jobId));
                                    gaSession.destroy();
                                }
                            }

                        }//while for write


                        buffer.clear();

                    }

                }//while command running

            }

            // 遇到关闭的链接可以忽略
            catch (ClosedChannelException e) {

                final String message = format("write failed, because socket broken. sessionId=%d;jobId=%d;\n",
                        gaSession.getSessionId(),
                        jobId);
                if (logger.isLoggable(Level.FINE)) {
                    logger.log(Level.FINE, message, e);
                }

            }

        }

        // 后续的一些处理
        finally {

            // 无论命令的结局如何，必须要关闭掉会话的写
            gaSession.markJobRunning(false);

            // 杀死后台JOB
            ProbeJobs.killJob(jobId);

        }
    }


    /*
     * 绘制提示符
     */
    private void reDrawPrompt(SocketChannel socketChannel, Charset charset) throws IOException {
        write(socketChannel, GaStringUtils.DEFAULT_PROMPT, charset);
    }

    private void write(SocketChannel socketChannel, String message, Charset charset) throws IOException {
        socketChannel.write(ByteBuffer.wrap((message).getBytes(charset)));
    }

    @Override
    public void destroy() {
        //
    }


}
