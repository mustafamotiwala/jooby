package io.jooby.handlebars;

import io.jooby.AttributeKey;
import io.jooby.MockContext;
import io.jooby.ModelAndView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class HbsTest {
  public static class User {
    private String firstname;

    private String lastname;

    public User(String firstname, String lastname) {
      this.firstname = firstname;
      this.lastname = lastname;
    }

    public String getFirstname() {
      return firstname;
    }

    public String getLastname() {
      return lastname;
    }
  }

  @Test
  public void render() throws Exception {
    Hbs engine = Hbs.builder().build();
    AttributeKey<String> local = new AttributeKey<>(String.class, "local");
    MockContext ctx = new MockContext();
    ctx.attributes().put(local, "var");
    String output = engine
        .apply(ctx, new ModelAndView("index.hbs")
            .put("user", new User("foo", "bar"))
            .put("sign", "!"));
    assertEquals("Hello foo bar var!\n", output);
  }
}