package org.phoebus.applications.git;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;
import org.phoebus.security.store.SecureStore;
import org.phoebus.ui.dialog.DialogHelper;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

/** Credentials for HTTPS and SSH, prompted in a dialog
 *
 *  <p>HTTPS user name and password (or access token) may be saved
 *  in the phoebus {@link SecureStore}, per host.
 *  SSH key passphrases and confirmation of unknown host keys
 *  are prompted each time.
 *
 *  <p>Called by JGit on a background thread, the dialog is shown
 *  on the UI thread while the background thread waits.
 */
@SuppressWarnings("nls")
class GitCredentialsProvider extends CredentialsProvider
{
    /** Hosts whose stored credentials failed, prompt for these */
    private static final Set<String> rejected = ConcurrentHashMap.newKeySet();

    private final Node owner;

    /** @param owner Node used to position the dialog */
    GitCredentialsProvider(final Node owner)
    {
        this.owner = owner;
    }

    @Override
    public boolean isInteractive()
    {
        return true;
    }

    @Override
    public boolean supports(final CredentialItem... items)
    {
        for (CredentialItem item : items)
            if (! (item instanceof CredentialItem.StringType      ||
                   item instanceof CredentialItem.CharArrayType   ||
                   item instanceof CredentialItem.YesNoType       ||
                   item instanceof CredentialItem.InformationalMessage))
                return false;
        return true;
    }

    @Override
    public boolean get(final URIish uri, final CredentialItem... items) throws UnsupportedCredentialItem
    {
        if (! supports(items))
            throw new UnsupportedCredentialItem(uri, "Unsupported credential item");
        if (isUserPassword(items)  &&  getStored(uri, items))
            return true;

        final CompletableFuture<Boolean> result = new CompletableFuture<>();
        Platform.runLater(() ->
        {
            try
            {
                result.complete(prompt(uri, items));
            }
            catch (Throwable ex)
            {
                result.completeExceptionally(ex);
            }
        });
        try
        {
            return result.get();
        }
        catch (Exception ex)
        {
            GitApp.logger.log(Level.WARNING, "Credentials prompt failed", ex);
            return false;
        }
    }

    /** Called by JGit when authentication failed */
    @Override
    public void reset(final URIish uri)
    {
        rejected.add(getTag(uri));
    }

    /** @return Are the items a user name and password prompt, as used by HTTPS? */
    private static boolean isUserPassword(final CredentialItem... items)
    {
        boolean user = false, password = false;
        for (CredentialItem item : items)
            if (item instanceof CredentialItem.Username)
                user = true;
            else if (item instanceof CredentialItem.Password)
                password = true;
            else if (! (item instanceof CredentialItem.InformationalMessage))
                return false;
        return user  &&  password;
    }

    /** @return SecureStore tag prefix for the host of the URI */
    private static String getTag(final URIish uri)
    {
        return "git:" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
    }

    /** Fill user name and password from the secure store
     *  @return <code>true</code> if stored credentials were used
     */
    private static boolean getStored(final URIish uri, final CredentialItem... items)
    {
        final String tag = getTag(uri);
        if (rejected.contains(tag))
            return false;
        try
        {
            final SecureStore store = new SecureStore();
            final String user = store.get(tag + ":" + SecureStore.USERNAME_TAG);
            final String password = store.get(tag + ":" + SecureStore.PASSWORD_TAG);
            if (user == null  ||  password == null)
                return false;
            for (CredentialItem item : items)
                if (item instanceof CredentialItem.Username u)
                    u.setValue(user);
                else if (item instanceof CredentialItem.Password p)
                    p.setValue(password.toCharArray());
            return true;
        }
        catch (Exception ex)
        {
            GitApp.logger.log(Level.WARNING, "Cannot read credentials for " + tag, ex);
            return false;
        }
    }

    private static void store(final URIish uri, final String user, final String password)
    {
        final String tag = getTag(uri);
        try
        {
            final SecureStore store = new SecureStore();
            store.set(tag + ":" + SecureStore.USERNAME_TAG, user);
            store.set(tag + ":" + SecureStore.PASSWORD_TAG, password);
            rejected.remove(tag);
        }
        catch (Exception ex)
        {
            GitApp.logger.log(Level.WARNING, "Cannot save credentials for " + tag, ex);
        }
    }

    /** Prompt for the items, must be called on UI thread
     *  @return <code>false</code> if cancelled
     */
    private boolean prompt(final URIish uri, final CredentialItem... items)
    {
        final Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(Messages.CredentialsTitle);
        dialog.setHeaderText(uri.setPass(null).toString());

        final GridPane grid = new GridPane();
        grid.setHgap(5);
        grid.setVgap(5);

        // Actions that transfer the entered values to the items
        final List<Runnable> apply = new ArrayList<>();
        final List<CredentialItem.YesNoType> yes_no = new ArrayList<>();
        final List<CheckBox> yes_no_boxes = new ArrayList<>();
        boolean have_fields = false;
        TextField user_field = null, password_field = null;
        int row = 0;
        for (CredentialItem item : items)
        {
            if (item instanceof CredentialItem.InformationalMessage)
            {
                final Label message = new Label(item.getPromptText());
                message.setWrapText(true);
                message.setMaxWidth(600);
                grid.add(message, 0, row++, 2, 1);
            }
            else if (item instanceof CredentialItem.YesNoType question)
            {
                final CheckBox check = new CheckBox(item.getPromptText());
                check.setWrapText(true);
                check.setMaxWidth(600);
                grid.add(check, 0, row++, 2, 1);
                yes_no.add(question);
                yes_no_boxes.add(check);
            }
            else
            {
                final TextField field = item.isValueSecure() ? new PasswordField() : new TextField();
                field.setPrefColumnCount(30);
                if (item instanceof CredentialItem.Username  &&  uri.getUser() != null)
                    field.setText(uri.getUser());
                grid.add(new Label(item.getPromptText()), 0, row);
                grid.add(field, 1, row++);
                if (item instanceof CredentialItem.StringType text)
                    apply.add(() -> text.setValue(field.getText()));
                else if (item instanceof CredentialItem.CharArrayType chars)
                    apply.add(() -> chars.setValue(field.getText().toCharArray()));
                if (item instanceof CredentialItem.Username)
                    user_field = field;
                else if (item instanceof CredentialItem.Password)
                    password_field = field;
                have_fields = true;
            }
        }

        final CheckBox save = new CheckBox(Messages.SaveCredentials);
        final boolean can_save = isUserPassword(items);
        if (can_save)
            grid.add(save, 1, row++);

        // A single question without input fields, for example to accept an unknown SSH host key,
        // is answered with Yes/No buttons instead of a check box
        final boolean question_only = ! have_fields  &&  yes_no.size() == 1;
        if (question_only)
        {
            grid.getChildren().remove(yes_no_boxes.get(0));
            final Label question = new Label(yes_no.get(0).getPromptText());
            question.setWrapText(true);
            question.setMaxWidth(600);
            grid.add(question, 0, row++, 2, 1);
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.YES, ButtonType.NO);
        }
        else
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.getDialogPane().setContent(grid);
        dialog.setResizable(true);
        DialogHelper.positionDialog(dialog, owner, -300, -200);
        if (user_field != null  &&  ! user_field.getText().isEmpty()  &&  password_field != null)
        {
            final TextField focus = password_field;
            Platform.runLater(focus::requestFocus);
        }

        final Optional<ButtonType> result = dialog.showAndWait();
        if (question_only)
        {
            if (result.isEmpty())
                return false;
            yes_no.get(0).setValue(result.get() == ButtonType.YES);
            return true;
        }
        if (result.isEmpty()  ||  result.get() != ButtonType.OK)
            return false;

        apply.forEach(Runnable::run);
        for (int i=0; i<yes_no.size(); ++i)
            yes_no.get(i).setValue(yes_no_boxes.get(i).isSelected());
        if (can_save  &&  save.isSelected())
            store(uri, user_field.getText(), password_field.getText());
        return true;
    }
}
