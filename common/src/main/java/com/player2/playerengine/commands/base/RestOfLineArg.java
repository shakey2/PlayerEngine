package com.player2.playerengine.commands.base;

/**
 * Consumes all remaining tokens on the command line as a single goal string.
 */
public final class RestOfLineArg extends ArgBase {

    private final String name;

    public RestOfLineArg(String name) {
        this.name = name;
    }

    @Override
    public boolean isArbitrarilyLong() {
        return true;
    }

    @Override
    public <V> V parseUnit(String unit, String[] unitPlusRemainder) throws CommandException {
        StringBuilder sb = new StringBuilder();
        if (unit != null && !unit.isEmpty()) {
            sb.append(unit);
        }
        if (unitPlusRemainder != null) {
            for (int i = 1; i < unitPlusRemainder.length; i++) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(unitPlusRemainder[i]);
            }
        }
        String text = sb.toString().trim();
        if (text.isEmpty()) {
            throw new CommandException("Goal text is required.");
        }
        return (V) new GoalText(text);
    }

    @Override
    public <V> V getDefault(Class<V> vType) {
        throw new IllegalStateException("RestOfLineArg has no default.");
    }

    @Override
    public String getHelpRepresentation() {
        return "<" + name + ">";
    }
}
