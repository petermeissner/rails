package net.sf.rails.game.specific._18Chesapeake;

import net.sf.rails.common.LocalText;
import net.sf.rails.common.ReportBuffer;
import net.sf.rails.game.Company;
import net.sf.rails.game.GameDef;
import net.sf.rails.game.GameManager;
import net.sf.rails.game.OperatingRound;
import net.sf.rails.game.PublicCompany; // Added import
import net.sf.rails.game.MapHex;         // Added import
import net.sf.rails.game.PrivateCompany;
import net.sf.rails.game.special.SpecialBaseTokenLay; // Added import
import net.sf.rails.game.special.SpecialProperty;
import net.sf.rails.game.special.SpecialSingleTileLay;
import net.sf.rails.game.state.ArrayListState;
import rails.game.action.NullAction;
import rails.game.action.PossibleAction;
import rails.game.action.LayTile;
import java.util.ArrayList;
import java.util.List;

public class OperatingRound_18Chesapeake extends OperatingRound {

    /** Hexes on which the operating company has laid a yellow tile this turn */
    private final ArrayListState<MapHex> yellowTileLaysThisTurn =
            new ArrayListState<>(this, "yellowTileLaysThisTurn");

    /*
     * Private companies P2 (CPR) and P3 (BSR) of the rulebook: the owning company
     * may lay two connected tiles, in addition to its normal tile lay. Any other
     * tile laid on one of the two hexes forfeits the ability.
     *
     * The rulebook leaves the following open; we read it as: both tiles are laid
     * together in the same turn, either before or after the normal tile lay.
     *
     * Each of the two tiles has its own special property in CompanyManager.xml,
     * which names the hex of the other tile as its neighbour.
     */

    /** Private companies of which a tile of the pair has been laid */
    private final ArrayListState<Company> startedTilePairs =
            new ArrayListState<>(this, "startedTilePairs");

    public OperatingRound_18Chesapeake(GameManager parent, String id) {
        super(parent, id);
    }

    @Override
    protected void initTurn() {
        super.initTurn();
        yellowTileLaysThisTurn.clear();
    }

    @Override
    public boolean layTile(LayTile action) {
        boolean result = super.layTile(action);
        if (result && action.getLaidTile() != null
                && "yellow".equalsIgnoreCase(action.getLaidTile().getColourText())) {
            yellowTileLaysThisTurn.add(action.getChosenHex());
        }
        return result;
    }

    /**
     * The free station of the C&amp;OC may only be placed immediately after the
     * company has laid the tile on that hex, and counts as its token lay.
     */
    private boolean isSpecialTokenLayAvailable(SpecialBaseTokenLay stl) {
        List<MapHex> locations = stl.getLocations();
        return !normalTokenLaidThisTurn.value() && locations != null
                && locations.stream().anyMatch(yellowTileLaysThisTurn::contains);
    }

    @Override
    protected void setSpecialTokenLays() {
        super.setSpecialTokenLays();
        currentSpecialTokenLays.removeIf(lay -> !isSpecialTokenLayAvailable(lay.getSpecialProperty()));

        PublicCompany company = (PublicCompany) operatingCompany.value();
        if (company == null || !company.canUseSpecialProperties() || company.getNumberOfFreeBaseTokens() == 0) {
            return;
        }

        // Also offer the free station if the company cannot pay for a normal one
        for (SpecialBaseTokenLay stl : getSpecialProperties(SpecialBaseTokenLay.class)) {
            if (!stl.isExtra() && currentNormalTokenLays.isEmpty() && isSpecialTokenLayAvailable(stl)) {
                List<MapHex> locations = stl.getLocations();
                if (locations != null && !locations.isEmpty()) {
                    boolean canLay = false;
                    for (MapHex location : locations) {
                        if (location.hasTokenOfCompany(company)) continue;
                        for (net.sf.rails.game.Stop stop : location.getStops()) {
                            canLay = !location.isBlockedForTokenLays(company, stop);
                        }
                    }
                    if (canLay) {
                        currentSpecialTokenLays.add(new rails.game.action.LayBaseToken(getRoot(), stl));
                    }
                }
            }
        }
    }

    private static boolean isPairedTileLay(SpecialProperty sp) {
        return sp instanceof SpecialSingleTileLay
                && ((SpecialSingleTileLay) sp).getNeighbouringHexes() != null;
    }

    @Override
    protected void afterTileLay(LayTile action) {
        SpecialProperty used = action.getSpecialProperty();
        if (isPairedTileLay(used)) {
            startedTilePairs.add(used.getOriginalCompany());
        }
        forfeitTilePairs(action.getChosenHex(), used);
    }

    /**
     * Any tile laid on one of the hexes other than by the private company's own
     * ability forfeits both tile lays.
     */
    private void forfeitTilePairs(MapHex hex, SpecialProperty used) {
        for (PrivateCompany priv : getRoot().getCompanyManager().getAllPrivateCompanies()) {
            if (priv.isClosed() || used != null && used.getOriginalCompany() == priv) continue;

            List<SpecialProperty> open = new ArrayList<>();
            boolean hexAffected = false;
            for (SpecialProperty sp : priv.getSpecialProperties()) {
                if (isPairedTileLay(sp) && !sp.isExercised()) {
                    open.add(sp);
                    hexAffected |= ((SpecialSingleTileLay) sp).getLocations().contains(hex);
                }
            }
            if (hexAffected) {
                open.forEach(SpecialProperty::setExercised);
                ReportBuffer.add(this, LocalText.getText("SpecialTileLayForfeited",
                        priv.getId(), hex.getId()));
            }
        }
    }

    @Override
    protected boolean mayLayExtraTileAfterNormalLay() {
        return getSpecialTileLays(false).stream()
                .anyMatch(lay -> isPairedTileLay(lay.getSpecialProperty()));
    }

    /** After the first tile of a pair, the second one is compulsory */
    @Override
    public boolean setPossibleActions() {
        boolean result = super.setPossibleActions();
        if (getStep() != GameDef.OrStep.LAY_TRACK) return result;

        List<LayTile> secondLays = new ArrayList<>();
        for (LayTile lay : possibleActions.getType(LayTile.class)) {
            if (isPairedTileLay(lay.getSpecialProperty())
                    && startedTilePairs.contains(lay.getSpecialProperty().getOriginalCompany())) {
                secondLays.add(lay);
            }
        }
        if (secondLays.isEmpty()) return result;

        // Remove all other tile lays and the ways to leave the step. The second lays
        // are recognised by identity, as LayTile.equals() considers the normal tile
        // lay equal to any other tile lay, so contains() would keep it.
        List<PossibleAction> remaining = new ArrayList<>();
        for (PossibleAction action : possibleActions.getList()) {
            boolean otherTileLay = action instanceof LayTile && secondLays.stream().noneMatch(lay -> lay == action)
                    && !((LayTile) action).isCorrection();
            boolean leaveStep = action instanceof NullAction
                    && (((NullAction) action).getMode() == NullAction.Mode.DONE
                    || ((NullAction) action).getMode() == NullAction.Mode.SKIP);
            if (!otherTileLay && !leaveStep) {
                remaining.add(action);
            }
        }
        possibleActions.clear();
        possibleActions.addAll(remaining);
        doneAllowed.set(false);
        return result;
    }

    @Override
    protected boolean validateSpecialTileLay(LayTile layTile) {
        return super.validateSpecialTileLay(layTile);
    }
}
