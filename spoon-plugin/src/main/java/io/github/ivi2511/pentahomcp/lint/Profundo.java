package io.github.ivi2511.pentahomcp.lint;

/** Qué hizo el modo profundo (§3.3). Lo llena LintSondas (WP3); el motor estático solo dice que no corrió. */
public final class Profundo {

  public boolean ran;
  public String reason;
  public long ms;
  public int probes;
}
