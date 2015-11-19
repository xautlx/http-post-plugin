package jenkins.plugins.httppost;

import com.squareup.okhttp.Headers;
import com.squareup.okhttp.MultipartBuilder;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.Run;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import java.net.URL;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Upload all {@link hudson.model.Run.Artifact artifacts} using a multipart HTTP POST call to an
 * specific URL.<br> Additional metadata will be included in the request as HTTP headers: {@code
 * Job-Name}, {@code Build-Number} and {@code Build-Timestamp} are included automatically by the
 * time writing.
 *
 * @author Christian Becker (christian.becker.1987@gmail.com)
 */
@SuppressWarnings("UnusedDeclaration")
// This class will be loaded using its Descriptor.
public class HttpPostPublisher extends Notifier {

    public String url;
    public String headers;

    @DataBoundConstructor
    public HttpPostPublisher(String url, String headers) {
        this.url = url;
        this.headers = headers;
    }

    @SuppressWarnings({ "unchecked", "deprecation" })
    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
        if (build.getResult().isWorseOrEqualTo(Result.FAILURE)) {
            listener.getLogger().println("HTTP POST: Skipping because of FAILURE");
            return true;
        }

        List<Run.Artifact> artifacts = build.getArtifacts();
        if (artifacts.isEmpty()) {
            listener.getLogger().println("HTTP POST: No artifacts to POST");
            return true;
        }

        Descriptor descriptor = getDescriptor();
        if (url == null || url.length() == 0) {
            url = descriptor.url;
        }
        if (headers == null || headers.length() == 0) {
            headers = descriptor.headers;
        }

        if (url == null || url.length() == 0) {
            listener.getLogger().println("HTTP POST: No URL specified");
            return true;
        }

        try {
            MultipartBuilder multipart = new MultipartBuilder();
            multipart.type(MultipartBuilder.FORM);
            for (Run.Artifact artifact : artifacts) {
                multipart.addFormDataPart(artifact.getFileName(), artifact.getFileName(), RequestBody.create(null, artifact.getFile()));
            }

            OkHttpClient client = new OkHttpClient();
            client.setConnectTimeout(30, TimeUnit.SECONDS);
            client.setReadTimeout(60, TimeUnit.SECONDS);

            Request.Builder builder = new Request.Builder();
            builder.url(url);
            String svn = System.getenv("SVN_REVISION");
            listener.getLogger().println(String.format("---> SVN_REVISION %s", svn));
            builder.header("SVN_REVISION", svn);
            builder.header("Job-Name", build.getProject().getName());
            builder.header("Build-Number", String.valueOf(build.getNumber()));
            builder.header("Build-Timestamp", String.valueOf(build.getTimeInMillis()));
            if (headers != null && headers.length() > 0) {
                String[] lines = headers.split("\r?\n");
                for (String line : lines) {
                    int index = line.indexOf(':');
                    builder.header(line.substring(0, index).trim(), line.substring(index + 1).trim());
                }
            }
            builder.post(multipart.build());

            Request request = builder.build();
            listener.getLogger().println(String.format("---> POST %s", url));
            listener.getLogger().println(request.headers());

            long start = System.nanoTime();
            Response response = client.newCall(request).execute();
            long time = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            listener.getLogger().println(String.format("<--- %s %s (%sms)", response.code(), response.message(), time));
            listener.getLogger().println(response.body().string());
        } catch (Exception e) {
            e.printStackTrace(listener.getLogger());
        }

        return true;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public Descriptor getDescriptor() {
        return (Descriptor) super.getDescriptor();
    }

    public FormValidation doCheckUrl(@QueryParameter String value) {
        return FormValidator.doCheckUrl(value);
    }

    public FormValidation doCheckHeaders(@QueryParameter String value) {
        return FormValidator.doCheckHeaders(value);
    }

    @Extension
    public static final class Descriptor extends BuildStepDescriptor<Publisher> {

        public String url;
        public String headers;

        public Descriptor() {
            super(HttpPostPublisher.class);
            load();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "HTTP POST artifacts to an URL";
        }

        public FormValidation doCheckUrl(@QueryParameter String value) {
            return FormValidator.doCheckUrl(value);
        }

        public FormValidation doCheckHeaders(@QueryParameter String value) {
            return FormValidator.doCheckHeaders(value);
        }

        /**
         * Handles SiteMonitor configuration for each job.
         * @param request
         *            the stapler request
         * @param json
         *            the JSON data containing job configuration values
         * @return the builder with specified sites to be monitored
         */
        @Override
        public final Publisher newInstance(final StaplerRequest request, final JSONObject json) {
            return new HttpPostPublisher(json.get("url") != null ? json.getString("url") : "",
                    json.get("headers") != null ? json.getString("headers") : "");
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            req.bindJSON(this, json.getJSONObject("http-post"));
            save();

            return true;
        }
    }

    private static class FormValidator {

        public static FormValidation doCheckUrl(@QueryParameter String value) {
            if (value.length() > 0) {
                if (!value.startsWith("http://") && !value.startsWith("https://")) {
                    return FormValidation.error("URL must start with http:// or https://");
                }

                try {
                    new URL(value).toURI();
                } catch (Exception e) {
                    return FormValidation.error(e.getMessage());
                }
            }

            return FormValidation.ok();
        }

        public static FormValidation doCheckHeaders(@QueryParameter String value) {
            if (value.length() > 0) {
                Headers.Builder headers = new Headers.Builder();
                String[] lines = value.split("\r?\n");

                for (String line : lines) {
                    int index = line.indexOf(':');
                    if (index == -1) {
                        return FormValidation.error("Unexpected header: " + line);
                    }

                    try {
                        headers.add(line.substring(0, index).trim(), line.substring(index + 1).trim());
                    } catch (Exception e) {
                        return FormValidation.error(e.getMessage());
                    }
                }
            }

            return FormValidation.ok();
        }
    }
}
